package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.media.config.InstanceId;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.mapper.TranscodeJobMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 转码作业(集群模型):同一 consumer group 的多个实例共同消费,作业领取靠
 * 租约抢占(PENDING/FAILED,或 RUNNING 且租约过期的失联作业),执行期间心跳续期,
 * 成功回写以"本实例持有"为围栏,迟到实例的写入不会生效。
 * 失败重试最多 3 次(MQ 重投递/租约回收驱动),超限终态并回写 TRANSCODE_FAILED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeService {

    private final TranscodeJobMapper jobMapper;
    private final MediaStorageService storageService;
    private final PythonScriptRunner scriptRunner;
    private final VideoInternalClient videoInternalClient;
    private final TranscodePolicy transcodePolicy;
    private final InstanceId instanceId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${toys.scripts.dir}")
    private String scriptsDir;

    @Value("${toys.transcode.lease-seconds:90}")
    private int leaseSeconds;

    @Value("${toys.transcode.heartbeat-seconds:30}")
    private int heartbeatSeconds;

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
        JsonNode result = null;
        ScheduledFuture<?> heartbeat = startHeartbeat(job.getId());

        try {
            // 1. 置 TRANSCODING;视频已在 TRANSCODING(租约抢回续跑)放行,其余冲突跳过
            var resp = videoInternalClient.updateStatus(videoId,
                    new VideoInternalClient.InternalStatusUpdate("TRANSCODING", null, null, null, null));
            if (resp != null && resp.code() == ErrorCode.VIDEO_STATUS_CONFLICT.getCode()
                    && !"TRANSCODING".equals(currentVideoStatus(videoId))) {
                log.warn("video {} not in APPROVED, skip transcode", videoId);
                releaseJob(job.getId(), "video status conflict");
                return;
            }

            // 2. 拉原片 → 转码 → 回传产物
            Path workDir = Files.createTempDirectory("transcode-");
            try {
                Path original = workDir.resolve("original.bin");
                storageService.downloadOriginal(objectKey, original);
                result = scriptRunner.run(Path.of(scriptsDir, "transcode.py"),
                        original.toString(), workDir.resolve("out").toString());
                storageService.uploadHls(videoId, workDir.resolve("out"));
                job.setPayload(result.toString());
            } finally {
                cleanup(workDir);
            }

            // 3. 作业先落 SUCCESS(限本实例持有的 RUNNING)——围栏拦截被抢回后的迟到写入;
            //    随后视频置 PUBLISHED,两步之间崩溃由 skip 分支补偿(见 repairStuckPublish)
            int done = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                    .eq(TranscodeJob::getId, job.getId())
                    .eq(TranscodeJob::getStatus, "RUNNING")
                    .eq(TranscodeJob::getOwnerInstance, instanceId.value())
                    .set(TranscodeJob::getStatus, "SUCCESS")
                    .set(TranscodeJob::getError, null)
                    .set(TranscodeJob::getPayload, job.getPayload(),
                            "typeHandler=com.toys.video.common.mybatis.JsonbTypeHandler")
                    .set(TranscodeJob::getFinishedAt, LocalDateTime.now())
                    .set(TranscodeJob::getLeaseUntil, null));
            if (done == 0) {
                log.warn("job for video {} superseded by another instance, drop result", videoId);
                return;
            }
            var ok = videoInternalClient.updateStatus(videoId,
                    new VideoInternalClient.InternalStatusUpdate("PUBLISHED",
                            longOrNull(result.path("duration_sec")), null, null, null));
            // 已被补偿路径置 PUBLISHED 的同态冲突视为成功
            if (ok != null && ok.code() != 0 && ok.code() != ErrorCode.VIDEO_STATUS_CONFLICT.getCode()) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "PUBLISHED 回写失败: " + ok.message());
            }
            log.info("video {} transcoded and PUBLISHED by {}", videoId, instanceId.value());
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("transcode failed for video {}: {}, {}",
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
                // 抛出让 MQ 延迟重试
                if (e instanceof BizException be) {
                    throw be;
                }
                throw new BizException(ErrorCode.INTERNAL_ERROR, "转码失败:" + error);
            }
        } finally {
            heartbeat.cancel(false);
        }
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

    /** 心跳续期:作业仍由本实例持有时刷新租约;丢失(被抢回)只告警,最终围栏兜底。 */
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

    private void releaseJob(Long jobId, String error) {
        jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .set(TranscodeJob::getStatus, "FAILED")
                .set(TranscodeJob::getError, error)
                .set(TranscodeJob::getLeaseUntil, null));
    }

    private void finalizeFailure(Long videoId, TranscodeJob job, String error) {
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

    private Long longOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asLong();
    }

    @PostConstruct
    void logLeaseConfig() {
        log.info("transcode cluster: instance={}, lease={}s, heartbeat={}s (Nacos 优先于本地默认值)",
                instanceId.value(), leaseSeconds, heartbeatSeconds);
    }

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }
}
