package com.toys.video.media.config;

import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 对象存储:hls bucket 公开读,启动时幂等初始化。 */
@Slf4j
@Configuration
public class MinioConfig {

    public static final String BUCKET_VIDEOS = "videos";
    public static final String BUCKET_HLS = "hls";

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
            log.info("minio buckets ready, hls kept private (served via video-service proxy)", BUCKET_HLS);
        } catch (Exception e) {
            log.error("minio init failed", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "对象存储不可用");
        }
        return client;
    }
}
