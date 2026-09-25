package com.toys.video.video.config;

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

/**
 * 对象存储:videos 私有(原片),hls 公开读(转码产物经网关 /media 代理)。
 * 启动时幂等初始化 bucket 与策略。
 */
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
            ensureBucket(client, BUCKET_VIDEOS, false);
            ensureBucket(client, BUCKET_HLS, true);
        } catch (Exception e) {
            log.error("minio bucket init failed", e);
        }
        return client;
    }

    private void ensureBucket(MinioClient client, String bucket, boolean publicRead) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("created minio bucket '{}'", bucket);
        }
        if (publicRead) {
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": {"AWS": ["*"]},
                        "Action": ["s3:GetObject"],
                        "Resource": ["arn:aws:s3:::%s/*"]
                      }]
                    }""".formatted(bucket);
            client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
            log.info("bucket '{}' set to public read", bucket);
        }
    }
}
