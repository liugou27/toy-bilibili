package com.toys.video.media.service;

import com.toys.video.media.config.MinioConfig;
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

/** MinIO 存取:原片下载与 HLS 产物上传。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaStorageService {

    private final MinioClient minioClient;

    public void downloadOriginal(String objectKey, Path target) {
        try (var in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(MinioConfig.BUCKET_VIDEOS)
                .object(objectKey)
                .build())) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new com.toys.video.common.exception.BizException(
                    com.toys.video.common.exception.ErrorCode.INTERNAL_ERROR, "下载原片失败");
        }
        log.info("downloaded original {} -> {}", objectKey, target);
    }

    public void uploadHls(Long videoId, Path outDir) throws IOException {
        try (Stream<Path> files = Files.walk(outDir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = outDir.relativize(file).toString().replace('\\', '/');
                String contentType = contentTypeOf(name);
                try (var in = Files.newInputStream(file)) {
                    minioClient.putObject(PutObjectArgs.builder()
                            .bucket(MinioConfig.BUCKET_HLS)
                            .object(videoId + "/" + name)
                            .stream(in, Files.size(file), -1)
                            .contentType(contentType)
                            .build());
                } catch (Exception e) {
                    throw new RuntimeException("upload " + name + " failed: " + e.getMessage(), e);
                }
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
