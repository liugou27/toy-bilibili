package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.mapper.TranscodeJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Stream;

/**
 * 转码作业:失败重试(最多 3 次,MQ 重投递驱动),超限终态并回写 TRANSCODE_FAILED。
 * 临时目录用完即删。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeService {

    private final TranscodeJobMapper jobMapper;
    private final MediaStorageService storageService;
    private final PythonScriptRunner scriptRunner;
    private final VideoInternalClient videoInternalClient;

    @org.springframework.beans.factory.annotation.Value("${toys.scripts.dir}")
    private String scriptsDir;

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
            return;
        }

        // 超过最大重试:终态,停止重试(ack 消息,等待人工在投稿页重试)
        if (job.getAttempts() >= job.getMaxAttempts()) {
            log.warn("video {} exceeded max attempts ({}), marking TRANSCODE_FAILED", videoId, job.getMaxAttempts());
            finalizeFailure(videoId, job, "转码重试次数用尽");
            return;
        }

        // 占位:RUNNING + attempts+1(条件更新,防并发重复消费)
        int rows = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, job.getId())
                .in(TranscodeJob::getStatus, "PENDING", "FAILED")
                .set(TranscodeJob::getStatus, "RUNNING")
                .set(TranscodeJob::getAttempts, job.getAttempts() + 1)
                .set(TranscodeJob::getStartedAt, LocalDateTime.now()));
        if (rows == 0) {
            log.info("job for video {} is already RUNNING, skip", videoId);
            return;
        }
        job.setAttempts(job.getAttempts() + 1);
        JsonNode result = null;

        try {
            // 1. 置 TRANSCODING(状态冲突说明视频不在 APPROVED,跳过)
            var resp = videoInternalClient.updateStatus(videoId,
                    new VideoInternalClient.InternalStatusUpdate("TRANSCODING", null, null, null, null));
            if (resp != null && resp.code() == ErrorCode.VIDEO_STATUS_CONFLICT.getCode()) {
                log.warn("video {} not in APPROVED, skip transcode", videoId);
                jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                        .eq(TranscodeJob::getId, job.getId())
                        .set(TranscodeJob::getStatus, "FAILED")
                        .set(TranscodeJob::getError, "video status conflict"));
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

            // 3. 成功回写
            jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                    .eq(TranscodeJob::getId, job.getId())
                    .set(TranscodeJob::getStatus, "SUCCESS")
                    .set(TranscodeJob::getError, null)
                    .set(TranscodeJob::getPayload, job.getPayload(),
                            "typeHandler=com.toys.video.media.handler.JsonbTypeHandler")
                    .set(TranscodeJob::getFinishedAt, LocalDateTime.now()));
            var ok = videoInternalClient.updateStatus(videoId,
                    new VideoInternalClient.InternalStatusUpdate("PUBLISHED",
                            longOrNull(result.path("duration_sec")), null, null, null));
            if (ok != null && ok.code() != 0) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "PUBLISHED 回写失败: " + ok.message());
            }
            log.info("video {} transcoded and PUBLISHED", videoId);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("transcode failed for video {} (attempt {}/{}): {}",
                    videoId, job.getAttempts(), job.getMaxAttempts(), error);
            if (job.getAttempts() >= job.getMaxAttempts()) {
                finalizeFailure(videoId, job, error);
            } else {
                jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                        .eq(TranscodeJob::getId, job.getId())
                        .set(TranscodeJob::getStatus, "FAILED")
                        .set(TranscodeJob::getError, error));
                // 抛出让 MQ 延迟重试
                if (e instanceof BizException be) {
                    throw be;
                }
                throw new BizException(ErrorCode.INTERNAL_ERROR, "转码失败:" + error);
            }
        }
    }

    private void finalizeFailure(Long videoId, TranscodeJob job, String error) {
        jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, job.getId())
                .set(TranscodeJob::getStatus, "FAILED")
                .set(TranscodeJob::getError, error)
                .set(TranscodeJob::getFinishedAt, LocalDateTime.now()));
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
}
