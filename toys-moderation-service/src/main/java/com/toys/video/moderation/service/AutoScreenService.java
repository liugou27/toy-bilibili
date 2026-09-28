package com.toys.video.moderation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.moderation.config.MinioConfig;
import com.toys.video.common.minio.MinioRetryExecutor;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 自动机审:拉原片 → Python 抽帧规则判定 → 清理临时目录。 */
@Slf4j
@Service
public class AutoScreenService {

    private final MinioClient minioClient;
    private final MinioRetryExecutor retryExecutor;
    private final PythonScriptRunner scriptRunner;
    private final Path autoScreenScript;

    public AutoScreenService(MinioClient minioClient,
                             MinioRetryExecutor retryExecutor,
                             PythonScriptRunner scriptRunner,
                             @Value("${toys.scripts.dir}") String scriptsDir) {
        this.minioClient = minioClient;
        this.retryExecutor = retryExecutor;
        this.scriptRunner = scriptRunner;
        this.autoScreenScript = Path.of(scriptsDir, "auto_screen.py");
    }

    public JsonNode screen(String objectKey) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("autoscreen-");
            Path original = workDir.resolve("original.bin");
            downloadOriginal(objectKey, original);
            log.info("downloaded original {} ({} bytes)", objectKey, Files.size(original));
            return scriptRunner.run(autoScreenScript,
                    original.toString(), "--frames", "8", "--workdir", workDir.toString());
        } catch (IOException e) {
            log.error("auto screen io error for {}", objectKey, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "机审下载原片失败");
        } finally {
            cleanup(workDir);
        }
    }

    /** 源对象已消失(视频删除)的标记异常:消费侧应 ACK 跳过而非重试。 */
    static class SourceVanishedException extends RuntimeException {
        SourceVanishedException(String objectKey) {
            super("source vanished: " + objectKey);
        }
    }

    /** 下载原片:经重试执行器,网络抖动自动退避重试。 */
    private void downloadOriginal(String objectKey, Path target) {
        retryExecutor.execute(() -> {
            try (var in = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(MinioConfig.BUCKET_VIDEOS)
                    .object(objectKey)
                    .build())) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                return null;
            } catch (io.minio.errors.ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())) {
                    // 源对象已不存在(视频被删除):机审无意义,标记后由消费侧静默确认
                    throw new SourceVanishedException(objectKey);
                }
                throw MinioRetryExecutor.unchecked(e);
            } catch (Exception e) {
                throw MinioRetryExecutor.unchecked(e);
            }
        }, "机审下载原片");
    }

    private void cleanup(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("cleanup failed for {}", dir);
        }
    }
}
