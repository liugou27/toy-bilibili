package com.toys.video.moderation.config;

import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MinioConfig {

    public static final String BUCKET_VIDEOS = "videos";

    @Bean
    public MinioClient minioClient(@Value("${toys.minio.endpoint}") String endpoint,
                                   @Value("${toys.minio.access-key}") String accessKey,
                                   @Value("${toys.minio.secret-key}") String secretKey) {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET_VIDEOS).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_VIDEOS).build());
            }
        } catch (Exception e) {
            log.error("minio init failed", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "对象存储不可用");
        }
        return client;
    }
}
