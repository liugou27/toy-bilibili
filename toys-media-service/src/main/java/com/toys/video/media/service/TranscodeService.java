package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.media.config.InstanceId;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import com.toys.video.media.mapper.TranscodeSegmentMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 转码编排(集群模型):VIDEO_APPROVED 触发本入口,内部完成
 * 切源建段(段作为子作业发往集群)→ 段并行转码(SegmentService)→ 组装发布(TranscodeAssembler)。
 * 作业领取靠租约抢占(PENDING/FAILED,或 RUNNING 且租约过期),执行期间心跳续期;
 * 任何实例可凭 DB 状态重入任意阶段:段未建→切源,段未齐→重派,段全成→组装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeService {

    private final TranscodeJobMapper jobMapper;
    private final TranscodeSegmentMapper segmentMapper;
    private final MediaStorageService storageService;
    private final PythonScriptRunner scriptRunner;
    private final VideoInternalClient videoInternalClient;
    private final TranscodePolicy transcodePolicy;
    private final InstanceId instanceId;
    private final SegmentService segmentService;
    private final TranscodeAssembler assembler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${toys.scripts.dir}")
    private String scriptsDir;

    @Value("${toys.transcode.lease-seconds:90}")
    private int leaseSeconds;

    @Value("${toys.transcode.heartbeat-seconds:30}")
    private int heartbeatSeconds;

    @Value("${toys.transcode.segment-seconds:60}")
    private int segmentSeconds;

    /** 单线程足够:每实例串行转码(consumeThreadNumber=1)。 */
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "transcode-lease-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public void process(Long videoId, String objectKey) {
        TranscodeJob job = jobMapper.selectOne(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getVideoId, videoId));
        if (job == null) {
            job = new TranscodeJob();
            job.setVideoId(videoId);
            job.setStatus("PENDING");
            job.setAttempts(0);
            job.setMaxAttempts(3);
            jobMapper.insert(job);
        } else if ("SUCCESS".equals(job.getStatus())) {
            log.info("job for video {} already SUCCESS, skip (idempotent)", videoId);
            repairStuckPublish(job);
            return;
        }

        // 超过最大重试:终态,停止重试(ack 消息,等待人工在投稿页重试)
        if (!transcodePolicy.shouldRetry(job.getAttempts(), job.getMaxAttempts())) {
            log.warn("video {} exceeded max attempts: {}, marking TRANSCODE_FAILED",
                    videoId, transcodePolicy.retryHint(job.getAttempts(), job.getMaxAttempts()));
            finalizeFailure(videoId, job, "转码重试次数用尽");
            return;
        }

        // 抢占:条件更新保证集群内只有一个实例领取(含租约过期的失联作业抢回)
        LocalDateTime now = LocalDateTime.now();
        int claimed = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, job.getId())
                .and(w -> w
                        .in(TranscodeJob::getStatus, "PENDING", "FAILED")
                        .or(o -> o.eq(TranscodeJob::getStatus, "RUNNING")
                                .and(l -> l.isNull(TranscodeJob::getLeaseUntil)
                                        .or().lt(TranscodeJob::getLeaseUntil, now))))
                .set(TranscodeJob::getStatus, "RUNNING")
                .set(TranscodeJob::getAttempts, job.getAttempts() + 1)
                .set(TranscodeJob::getOwnerInstance, instanceId.value())
                .set(TranscodeJob::getLeaseUntil, now.plusSeconds(leaseSeconds))
                .set(TranscodeJob::getObjectKey, objectKey)
                .set(TranscodeJob::getStartedAt, now));
        if (claimed == 0) {
            log.info("job for video {} is RUNNING under a live lease, skip", videoId);
            return;
        }
        job.setAttempts(job.getAttempts() + 1);
        log.info("job for video {} claimed by {} (attempt {}/{})",
                videoId, instanceId.value(), job.getAttempts(), job.getMaxAttempts());
        ScheduledFuture<?> heartbeat = startHeartbeat(job.getId());

        try {
            List<TranscodeSegment> segments = segmentMapper.selectList(
                    new LambdaQueryWrapper<TranscodeSegment>()
                            .eq(TranscodeSegment::getJobId, job.getId())
                            .orderByAsc(TranscodeSegment::getSegIndex));

            if (segments.isEmpty()) {
                splitAndEnqueue(job, objectKey);
                return;
            }
            if (segments.stream().allMatch(s -> "SUCCESS".equals(s.getStatus()))) {
                takeOverAndAssemble(job, segments);
                return;
            }
            // 重入(切源实例失联/部分段未派发):重置 TRANSCODING 并重派未完成段
            markTranscoding(videoId);
            segmentService.redispatchIncomplete(segments);
            jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                    .eq(TranscodeJob::getId, job.getId())
                    .eq(TranscodeJob::getStatus, "RUNNING")
                    .eq(TranscodeJob::getOwnerInstance, instanceId.value())
                    .set(TranscodeJob::getStatus, "WAITING")
                    .set(TranscodeJob::getLeaseUntil, null));
            log.info("job for video {} re-dispatched {} incomplete segment(s), WAITING",
                    videoId, segments.stream().filter(s -> !"SUCCESS".equals(s.getStatus())).count());
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("orchestrate failed for video {}: {}, {}",
                    videoId, error, transcodePolicy.retryHint(job.getAttempts(), job.getMaxAttempts()));
            if (!transcodePolicy.shouldRetry(job.getAttempts(), job.getMaxAttempts())) {
                finalizeFailure(videoId, job, error);
            } else {
                jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                        .eq(TranscodeJob::getId, job.getId())
                        .eq(TranscodeJob::getStatus, "RUNNING")
                        .set(TranscodeJob::getStatus, "FAILED")
                        .set(TranscodeJob::getError, error)
                        .set(TranscodeJob::getLeaseUntil, null));
                if (e instanceof BizException be) {
                    throw be;
                }
                throw new BizException(ErrorCode.INTERNAL_ERROR, "转码失败:" + error);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    /**
     * 切源:下载原片→关键帧无损切段→上传段源与封面→建段子作业→发段消息→作业转 WAITING。
     */
    private void splitAndEnqueue(TranscodeJob job, String objectKey) throws Exception {
        Long videoId = job.getVideoId();
        if (!markTranscoding(videoId)) {
            log.warn("video {} not in APPROVED/TRANSCODING, skip transcode", videoId);
            releaseJob(job.getId(), "video status conflict");
            return;
        }
        Path workDir = Files.createTempDirectory("split-");
        try {
            Path original = workDir.resolve("original.bin");
            storageService.downloadOriginal(objectKey, original);
            JsonNode result = scriptRunner.run(Path.of(scriptsDir, "split_media.py"),
                    original.toString(), workDir.resolve("segments").toString(),
                    String.valueOf(segmentSeconds));

            boolean single = result.path("single").asBoolean(false);
            String ladder = joinLadder(result.path("ladder"));
            storageService.uploadFile(com.toys.video.media.config.MinioConfig.BUCKET_HLS,
                    videoId + "/poster.jpg", workDir.resolve("segments").resolve(result.path("poster").asText()),
                    "image/jpeg");

            List<TranscodeSegment> created = new ArrayList<>();
            for (JsonNode seg : result.path("segments")) {
                int index = seg.path("index").asInt();
                TranscodeSegment row = new TranscodeSegment();
                row.setJobId(job.getId());
                row.setVideoId(videoId);
                row.setSegIndex(index);
                row.setDurationSec(seg.path("duration_sec").asDouble());
                row.setLadder(ladder);
                row.setStatus("PENDING");
                row.setAttempts(0);
                row.setMaxAttempts(3);
                if (single) {
                    row.setObjectKey(objectKey);
                } else {
                    String segKey = "segments/" + videoId + "/" + String.format("%04d", index) + ".mp4";
                    storageService.uploadFile(com.toys.video.media.config.MinioConfig.BUCKET_VIDEOS,
                            segKey, workDir.resolve("segments").resolve(seg.path("file").asText()),
                            "video/mp4");
                    row.setObjectKey(segKey);
                }
                segmentMapper.insert(row);
                created.add(row);
            }
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("duration_sec", result.path("duration_sec").asDouble());
            payload.put("ladder", ladder);
            payload.put("segments", created.size());
            job.setPayload(payload.toString());

            segmentService.dispatch(created);
            jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                    .eq(TranscodeJob::getId, job.getId())
                    .eq(TranscodeJob::getStatus, "RUNNING")
                    .eq(TranscodeJob::getOwnerInstance, instanceId.value())
                    .set(TranscodeJob::getStatus, "WAITING")
                    .set(TranscodeJob::getLeaseUntil, null)
                    .set(TranscodeJob::getPayload, job.getPayload(),
                            "typeHandler=com.toys.video.common.mybatis.JsonbTypeHandler"));
            log.info("video {} split into {} segment(s), ladder [{}], target {}s each, dispatched to cluster",
                    videoId, created.size(), ladder, segmentSeconds);
        } finally {
            cleanup(workDir);
        }
    }

    /** 段全部完成:抢占 WAITING/RUNNING→MERGING,赢家就地组装。 */
    private void takeOverAndAssemble(TranscodeJob job, List<TranscodeSegment> segments) {
        int took = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, job.getId())
                .in(TranscodeJob::getStatus, "WAITING", "RUNNING")
                .eq(TranscodeJob::getOwnerInstance, instanceId.value())
                .set(TranscodeJob::getStatus, "MERGING")
                .set(TranscodeJob::getLeaseUntil, LocalDateTime.now().plusSeconds(leaseSeconds * 3L)));
        if (took == 0) {
            log.info("job for video {} merging taken by another instance, skip", job.getVideoId());
            return;
        }
        assembler.assemble(job, segments);
    }

    /** 置 TRANSCODING;已在 TRANSCODING(重入续跑)放行。 */
    private boolean markTranscoding(Long videoId) {
        var resp = videoInternalClient.updateStatus(videoId,
                new VideoInternalClient.InternalStatusUpdate("TRANSCODING", null, null, null, null));
        if (resp != null && resp.code() == ErrorCode.VIDEO_STATUS_CONFLICT.getCode()) {
            return "TRANSCODING".equals(currentVideoStatus(videoId));
        }
        return true;
    }

    /** 作业 SUCCESS 但视频仍停 TRANSCODING(成功落库与回写之间崩溃)时补推 PUBLISHED。 */
    private void repairStuckPublish(TranscodeJob job) {
        try {
            if (!"TRANSCODING".equals(currentVideoStatus(job.getVideoId()))) {
                return;
            }
            Long duration = null;
            if (job.getPayload() != null) {
                duration = longOrNull(objectMapper.readTree(job.getPayload()).path("duration_sec"));
            }
            videoInternalClient.updateStatus(job.getVideoId(),
                    new VideoInternalClient.InternalStatusUpdate("PUBLISHED", duration, null, null, null));
            log.info("repaired stuck publish for video {}", job.getVideoId());
        } catch (Exception e) {
            log.warn("repair stuck publish failed for video {}: {}", job.getVideoId(), e.getMessage());
        }
    }

    private String currentVideoStatus(Long videoId) {
        try {
            var resp = videoInternalClient.batch(java.util.List.of(videoId));
            if (resp != null && resp.code() == 0 && resp.data() != null && !resp.data().isEmpty()) {
                return resp.data().get(0).status();
            }
        } catch (Exception e) {
            log.warn("query video status failed for {}: {}", videoId, e.getMessage());
        }
        return null;
    }

    /** 心跳续期:作业仍由本实例持有时刷新租约;丢失(被抢回)只告警,围栏兜底。 */
    private ScheduledFuture<?> startHeartbeat(Long jobId) {
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                int rows = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                        .eq(TranscodeJob::getId, jobId)
                        .eq(TranscodeJob::getStatus, "RUNNING")
                        .eq(TranscodeJob::getOwnerInstance, instanceId.value())
                        .set(TranscodeJob::getLeaseUntil, LocalDateTime.now().plusSeconds(leaseSeconds)));
                if (rows == 0) {
                    log.warn("lease lost for job {}, result will be dropped if superseded", jobId);
                }
            } catch (Exception e) {
                log.warn("heartbeat failed for job {}: {}", jobId, e.getMessage());
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    void releaseJob(Long jobId, String error) {
        jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .set(TranscodeJob::getStatus, "FAILED")
                .set(TranscodeJob::getError, error)
                .set(TranscodeJob::getLeaseUntil, null));
    }

    void finalizeFailure(Long videoId, TranscodeJob job, String error) {
        jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, job.getId())
                .set(TranscodeJob::getStatus, "FAILED")
                .set(TranscodeJob::getError, error)
                .set(TranscodeJob::getFinishedAt, LocalDateTime.now())
                .set(TranscodeJob::getLeaseUntil, null));
        videoInternalClient.updateStatus(videoId,
                new VideoInternalClient.InternalStatusUpdate("TRANSCODE_FAILED", null, null, null,
                        "转码失败:" + error));
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

    private String joinLadder(JsonNode ladder) {
        List<String> heights = new ArrayList<>();
        ladder.forEach(h -> heights.add(h.asText()));
        return String.join(",", heights);
    }

    private Long longOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : (long) n.asDouble();
    }

    @jakarta.annotation.PostConstruct
    void logLeaseConfig() {
        log.info("transcode cluster: instance={}, lease={}s, heartbeat={}s, segment={}s (Nacos 优先于本地默认值)",
                instanceId.value(), leaseSeconds, heartbeatSeconds, segmentSeconds);
    }

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }
}
