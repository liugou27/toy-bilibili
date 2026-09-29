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
        downloadFrom(MinioConfig.BUCKET_VIDEOS, objectKey, target, "原片");
    }

    /** 通用下载:段源/中间播放表等。 */
    public void downloadFrom(String bucket, String objectKey, Path target, String what) {
        retryExecutor.execute(() -> {
            try (var in = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build())) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return null;
            } catch (Exception e) {
                throw MinioRetryExecutor.unchecked(e);
            }
        }, "下载" + what);
    }

    /** 通用单文件上传(封面/播放表等)。 */
    public void uploadFile(String bucket, String object, Path file, String contentType) {
        retryExecutor.execute(() -> {
            try (var in = Files.newInputStream(file)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(object)
                        .stream(in, Files.size(file), -1)
                        .contentType(contentType)
                        .build());
                return null;
            } catch (Exception e) {
                throw MinioRetryExecutor.unchecked(e);
            }
        }, "上传 " + object);
    }

    /** 删除单个对象(中间播放表清理)。 */
    public void deleteObject(String bucket, String object) {
        try {
            minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                    .bucket(bucket).object(object).build());
        } catch (Exception e) {
            log.warn("object delete failed: {} ({})", object, e.getMessage());
        }
    }

    /** 删除指定前缀下全部对象(段源/产物清理)。 */
    public void removePrefix(String bucket, String prefix) {
        try {
            var objects = new java.util.ArrayList<io.minio.messages.DeleteObject>();
            for (io.minio.Result<io.minio.messages.Item> r : minioClient.listObjects(
                    io.minio.ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
                objects.add(new io.minio.messages.DeleteObject(r.get().objectName()));
            }
            if (objects.isEmpty()) {
                return;
            }
            for (io.minio.Result<io.minio.messages.DeleteError> err : minioClient.removeObjects(
                    io.minio.RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build())) {
                log.warn("prefix object delete failed: {}", err.get().objectName());
            }
        } catch (Exception e) {
            log.warn("prefix cleanup failed, bucket={}, prefix={}: {}", bucket, prefix, e.getMessage());
        }
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
