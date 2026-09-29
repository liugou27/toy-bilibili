package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toys.video.api.event.Topics;
import com.toys.video.api.event.VideoSegmentEvent;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.media.config.InstanceId;
import com.toys.video.media.config.MinioConfig;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import com.toys.video.media.mapper.TranscodeSegmentMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 段转码执行:集群内任意实例凭租约抢占段子作业,下载段源→多档转码→上传产物→SUCCESS。
 * 最后一个段落定(全部 SUCCESS 或出现终态失败)的实例负责推进父作业:
 * 全成→抢占 MERGING 并组装;有失败且无在途→父作业终态 TRANSCODE_FAILED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentService {

    private final TranscodeSegmentMapper segmentMapper;
    private final TranscodeJobMapper jobMapper;
    private final MediaStorageService storageService;
    private final PythonScriptRunner scriptRunner;
    private final VideoInternalClient videoInternalClient;
    private final TranscodeAssembler assembler;
    private final InstanceId instanceId;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${toys.scripts.dir}")
    private String scriptsDir;

    @Value("${toys.transcode.lease-seconds:90}")
    private int leaseSeconds;

    @Value("${toys.transcode.heartbeat-seconds:30}")
    private int heartbeatSeconds;

    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "segment-lease-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 切源完成后批量派发段消息(快路径;消息丢失由 LeaseReaper 兜底重派)。 */
    public void dispatch(List<TranscodeSegment> segments) {
        for (TranscodeSegment seg : segments) {
            rocketMQTemplate.syncSend(Topics.VIDEO_TRANSCODE_SEGMENT,
                    new VideoSegmentEvent(seg.getVideoId(), seg.getJobId(), seg.getId()));
        }
        log.info("dispatched {} segment task(s) to cluster", segments.size());
    }

    /** 重入恢复:对未完成且无人持有的段重新派发。 */
    public void redispatchIncomplete(List<TranscodeSegment> segments) {
        int resent = 0;
        for (TranscodeSegment seg : segments) {
            boolean idle = "PENDING".equals(seg.getStatus()) || "FAILED".equals(seg.getStatus())
                    || ("RUNNING".equals(seg.getStatus())
                        && (seg.getLeaseUntil() == null || seg.getLeaseUntil().isBefore(LocalDateTime.now())));
            if (idle && seg.getAttempts() < seg.getMaxAttempts()) {
                rocketMQTemplate.syncSend(Topics.VIDEO_TRANSCODE_SEGMENT,
                        new VideoSegmentEvent(seg.getVideoId(), seg.getJobId(), seg.getId()));
                resent++;
            }
        }
        if (resent > 0) {
            log.info("re-dispatched {} idle/incomplete segment(s)", resent);
        }
    }

    public void processSegment(Long segmentId) {
        TranscodeSegment seg = segmentMapper.selectById(segmentId);
        if (seg == null || "SUCCESS".equals(seg.getStatus())) {
            return;
        }
        if (seg.getAttempts() >= seg.getMaxAttempts()) {
            log.warn("segment {} exhausted attempts, terminal fail", segmentId);
            failSegment(seg, "段转码重试次数用尽", true);
            completeCheck(seg);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int claimed = segmentMapper.update(null, new LambdaUpdateWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getId, seg.getId())
                .and(w -> w
                        .in(TranscodeSegment::getStatus, "PENDING", "FAILED")
                        .or(o -> o.eq(TranscodeSegment::getStatus, "RUNNING")
                                .and(l -> l.isNull(TranscodeSegment::getLeaseUntil)
                                        .or().lt(TranscodeSegment::getLeaseUntil, now))))
                .set(TranscodeSegment::getStatus, "RUNNING")
                .set(TranscodeSegment::getAttempts, seg.getAttempts() + 1)
                .set(TranscodeSegment::getOwnerInstance, instanceId.value())
                .set(TranscodeSegment::getLeaseUntil, now.plusSeconds(leaseSeconds)));
        if (claimed == 0) {
            log.info("segment {} is RUNNING under a live lease, skip", segmentId);
            return;
        }
        seg.setAttempts(seg.getAttempts() + 1);
        log.info("segment {} (video {}, index {}, attempt {}/{}) claimed by {}",
                segmentId, seg.getVideoId(), seg.getSegIndex(),
                seg.getAttempts(), seg.getMaxAttempts(), instanceId.value());
        ScheduledFuture<?> heartbeat = startHeartbeat(seg.getId());

        try {
            Path workDir = Files.createTempDirectory("segment-");
            try {
                Path src = workDir.resolve("seg.mp4");
                storageService.downloadFrom(MinioConfig.BUCKET_VIDEOS, seg.getObjectKey(), src, "段源");
                Path out = workDir.resolve("out");
                scriptRunner.run(Path.of(scriptsDir, "transcode_segment.py"),
                        src.toString(), out.toString(),
                        String.valueOf(seg.getSegIndex()), seg.getLadder());
                storageService.uploadHls(seg.getVideoId(), out);
            } finally {
                cleanup(workDir);
            }

            int done = segmentMapper.update(null, new LambdaUpdateWrapper<TranscodeSegment>()
                    .eq(TranscodeSegment::getId, seg.getId())
                    .eq(TranscodeSegment::getStatus, "RUNNING")
                    .eq(TranscodeSegment::getOwnerInstance, instanceId.value())
                    .set(TranscodeSegment::getStatus, "SUCCESS")
                    .set(TranscodeSegment::getError, null)
                    .set(TranscodeSegment::getLeaseUntil, null));
            if (done == 0) {
                log.warn("segment {} superseded by another instance, drop result", segmentId);
                return;
            }
            log.info("segment {} (video {}) transcoded by {}", segmentId, seg.getVideoId(), instanceId.value());
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("segment {} transcode failed: {}", segmentId, error);
            boolean terminal = seg.getAttempts() >= seg.getMaxAttempts();
            failSegment(seg, error, terminal);
            completeCheck(seg);
            if (!terminal) {
                // 抛出让 MQ 延迟重试
                throw new com.toys.video.common.exception.BizException(
                        com.toys.video.common.exception.ErrorCode.INTERNAL_ERROR, "段转码失败:" + error);
            }
            return;
        } finally {
            heartbeat.cancel(false);
        }
        completeCheck(seg);
    }

    /**
     * 段落定后的父作业推进:无在途段时,全 SUCCESS→抢 MERGING 组装;有 FAILED→父终态。
     */
    void completeCheck(TranscodeSegment seg) {
        Long unfinished = segmentMapper.selectCount(new LambdaQueryWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getJobId, seg.getJobId())
                .ne(TranscodeSegment::getStatus, "SUCCESS")
                .ne(TranscodeSegment::getStatus, "FAILED"));
        if (unfinished != null && unfinished > 0) {
            return;
        }
        Long failed = segmentMapper.selectCount(new LambdaQueryWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getJobId, seg.getJobId())
                .eq(TranscodeSegment::getStatus, "FAILED"));
        if (failed != null && failed > 0) {
            finalizeJobFailure(seg.getJobId(), "段转码失败(" + failed + " 段终态失败)");
            return;
        }
        TranscodeJob job = jobMapper.selectById(seg.getJobId());
        if (job == null || "SUCCESS".equals(job.getStatus())) {
            return;
        }
        int took = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, job.getId())
                .in(TranscodeJob::getStatus, "WAITING", "RUNNING")
                .set(TranscodeJob::getStatus, "MERGING")
                .set(TranscodeJob::getOwnerInstance, instanceId.value())
                .set(TranscodeJob::getLeaseUntil, LocalDateTime.now().plusSeconds(leaseSeconds * 3L)));
        if (took == 0) {
            log.info("job {} merging taken by another instance, skip", job.getId());
            return;
        }
        List<TranscodeSegment> all = segmentMapper.selectList(new LambdaQueryWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getJobId, job.getId())
                .orderByAsc(TranscodeSegment::getSegIndex));
        Thread.ofVirtual().name("assemble-" + job.getVideoId())
                .start(() -> {
                    try {
                        assembler.assemble(job, all);
                    } catch (Exception e) {
                        log.error("assemble failed for video {}: {}", job.getVideoId(), e.getMessage());
                    }
                });
    }

    /** 段失败落库(持有围栏),租约释放。 */
    private void failSegment(TranscodeSegment seg, String error, boolean terminal) {
        segmentMapper.update(null, new LambdaUpdateWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getId, seg.getId())
                .eq(TranscodeSegment::getStatus, "RUNNING")
                .eq(TranscodeSegment::getOwnerInstance, instanceId.value())
                .set(TranscodeSegment::getStatus, "FAILED")
                .set(TranscodeSegment::getError, error)
                .set(TranscodeSegment::getLeaseUntil, null));
    }

    private void finalizeJobFailure(Long jobId, String error) {
        int rows = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .in(TranscodeJob::getStatus, "WAITING", "RUNNING", "MERGING")
                .set(TranscodeJob::getStatus, "FAILED")
                .set(TranscodeJob::getError, error)
                .set(TranscodeJob::getFinishedAt, LocalDateTime.now())
                .set(TranscodeJob::getLeaseUntil, null));
        if (rows == 0) {
            return;
        }
        TranscodeJob job = jobMapper.selectById(jobId);
        if (job != null) {
            videoInternalClient.updateStatus(job.getVideoId(),
                    new VideoInternalClient.InternalStatusUpdate("TRANSCODE_FAILED", null, null, null,
                            "转码失败:" + error));
            log.warn("job {} terminal fail: {}", jobId, error);
        }
    }

    private ScheduledFuture<?> startHeartbeat(Long segmentId) {
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                int rows = segmentMapper.update(null, new LambdaUpdateWrapper<TranscodeSegment>()
                        .eq(TranscodeSegment::getId, segmentId)
                        .eq(TranscodeSegment::getStatus, "RUNNING")
                        .eq(TranscodeSegment::getOwnerInstance, instanceId.value())
                        .set(TranscodeSegment::getLeaseUntil, LocalDateTime.now().plusSeconds(leaseSeconds)));
                if (rows == 0) {
                    log.warn("lease lost for segment {}, result will be dropped if superseded", segmentId);
                }
            } catch (Exception e) {
                log.warn("segment heartbeat failed for {}: {}", segmentId, e.getMessage());
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    private void cleanup(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (Exception e) {
            log.warn("cleanup failed for {}", dir);
        }
    }

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }
}
