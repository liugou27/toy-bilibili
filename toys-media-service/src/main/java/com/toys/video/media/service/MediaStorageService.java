package com.toys.video.media.service;

import com.toys.video.media.config.MinioConfig;
import com.toys.video.common.minio.MinioRetryExecutor;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/** MinIO 存取:原片下载与 HLS 产物上传,单文件操作经重试执行器抗网络抖动。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaStorageService {

    private final MinioClient minioClient;
    private final MinioRetryExecutor retryExecutor;

    public void downloadOriginal(String objectKey, Path target) {
        retryExecutor.execute(() -> {
            try (var in = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(MinioConfig.BUCKET_VIDEOS)
                    .object(objectKey)
                    .build())) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return null;
            } catch (Exception e) {
                throw MinioRetryExecutor.unchecked(e);
            }
        }, "下载原片");
        log.info("downloaded original {} -> {}", objectKey, target);
    }

    public void uploadHls(Long videoId, Path outDir) throws IOException {
        try (Stream<Path> files = Files.walk(outDir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = outDir.relativize(file).toString().replace('\\', '/');
                String contentType = contentTypeOf(name);
                // 单文件粒度重试:每次尝试重新打开文件流,已成功的分片不受影响
                retryExecutor.execute(() -> {
                    try (var in = Files.newInputStream(file)) {
                        minioClient.putObject(PutObjectArgs.builder()
                                .bucket(MinioConfig.BUCKET_HLS)
                                .object(videoId + "/" + name)
                                .stream(in, Files.size(file), -1)
                                .contentType(contentType)
                                .build());
                        return null;
                    } catch (Exception e) {
                        throw MinioRetryExecutor.unchecked(e);
                    }
                }, "上传 " + name);
            });
        }
        log.info("uploaded hls artifacts for video {}", videoId);
    }

    private String contentTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (lower.endsWith(".ts")) {
            return "video/mp2t";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }
}
