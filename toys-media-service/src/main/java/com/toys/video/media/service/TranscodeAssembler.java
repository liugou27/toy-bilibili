package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.media.config.InstanceId;
import com.toys.video.media.config.MinioConfig;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Stream;

/**
 * 组装发布:下载各段中间播放表→拼接各档 media playlist 与 master→上传→清理中间表→
 * 作业 SUCCESS(限本实例持有的 MERGING,围栏拦截迟到写入)→视频 PUBLISHED。
 * 组装为秒级本地操作,MERGING 租约按 3 倍宽放,超时由 LeaseReaper 重派幂等组装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeAssembler {

    private final TranscodeJobMapper jobMapper;
    private final MediaStorageService storageService;
    private final PythonScriptRunner scriptRunner;
    private final VideoInternalClient videoInternalClient;
    private final InstanceId instanceId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${toys.scripts.dir}")
    private String scriptsDir;

    public void assemble(TranscodeJob job, java.util.List<TranscodeSegment> segments) {
        Long videoId = job.getVideoId();
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("assemble-");
            Path inDir = workDir.resolve("in");
            Path outDir = workDir.resolve("out");
            Files.createDirectories(inDir);

            String ladder = segments.get(0).getLadder();
            for (TranscodeSegment seg : segments) {
                for (String height : ladder.split(",")) {
                    String name = height + "p_" + String.format("%04d", seg.getSegIndex()) + ".m3u8";
                    storageService.downloadFrom(MinioConfig.BUCKET_HLS, videoId + "/" + name,
                            inDir.resolve(name), "段播放表");
                }
            }
            JsonNode result = scriptRunner.run(Path.of(scriptsDir, "merge_playlists.py"),
                    inDir.toString(), outDir.toString(), ladder, String.valueOf(segments.size()));
            storageService.uploadHls(videoId, outDir);

            // 中间播放表使命完成,删除(段分片保留为最终产物)
            for (TranscodeSegment seg : segments) {
                for (String height : ladder.split(",")) {
                    storageService.deleteObject(MinioConfig.BUCKET_HLS,
                            videoId + "/" + height + "p_" + String.format("%04d", seg.getSegIndex()) + ".m3u8");
                }
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("renditions", result.path("renditions"));
            payload.put("poster", "poster.jpg");
            payload.put("duration_sec", sourceDuration(job));
            job.setPayload(payload.toString());

            int done = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                    .eq(TranscodeJob::getId, job.getId())
                    .eq(TranscodeJob::getStatus, "MERGING")
                    .eq(TranscodeJob::getOwnerInstance, instanceId.value())
                    .set(TranscodeJob::getStatus, "SUCCESS")
                    .set(TranscodeJob::getError, null)
                    .set(TranscodeJob::getPayload, job.getPayload(),
                            "typeHandler=com.toys.video.common.mybatis.JsonbTypeHandler")
                    .set(TranscodeJob::getFinishedAt, LocalDateTime.now())
                    .set(TranscodeJob::getLeaseUntil, null));
            if (done == 0) {
                log.warn("job for video {} superseded during merge, drop result", videoId);
                return;
            }
            var ok = videoInternalClient.updateStatus(videoId,
                    new VideoInternalClient.InternalStatusUpdate("PUBLISHED",
                            payload.path("duration_sec").asLong(), null, null, null));
            if (ok != null && ok.code() != 0 && ok.code() != ErrorCode.VIDEO_STATUS_CONFLICT.getCode()) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "PUBLISHED 回写失败: " + ok.message());
            }
            log.info("video {} assembled ({} segments, ladder {}) and PUBLISHED by {}",
                    videoId, segments.size(), ladder, instanceId.value());
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("assemble failed for video {}: {}", videoId, error);
            // 回到 WAITING 交由 LeaseReaper 重派组装(段全 SUCCESS 的 WAITING 会被兜底扫描)
            jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                    .eq(TranscodeJob::getId, job.getId())
                    .eq(TranscodeJob::getStatus, "MERGING")
                    .eq(TranscodeJob::getOwnerInstance, instanceId.value())
                    .set(TranscodeJob::getStatus, "WAITING")
                    .set(TranscodeJob::getError, "组装失败重试: " + error)
                    .set(TranscodeJob::getLeaseUntil, null));
        } finally {
            cleanup(workDir);
        }
    }

    /** 源时长(切源时落库的 payload);读不到时退化 0。 */
    private double sourceDuration(TranscodeJob job) {
        try {
            if (job.getPayload() != null) {
                JsonNode node = objectMapper.readTree(job.getPayload()).path("duration_sec");
                if (node.isNumber()) {
                    return node.asDouble();
                }
            }
        } catch (IOException ignored) {
            // payload 解析失败按 0 处理
        }
        return 0d;
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
}
