package com.toys.video.video.config;

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
            ensureBucket(client, BUCKET_VIDEOS);
            ensureBucket(client, BUCKET_HLS);
            // hls 桶强制私有:媒体一律经 /media/** 服务端代理流出;
            // 历史部署可能带 public-read 策略,启动时覆盖为"空语句表"策略(等效私有,SDK 拒绝空串)
            client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(BUCKET_HLS)
                    .config("{\"Version\":\"2012-10-17\",\"Statement\":[]}").build());
        } catch (Exception e) {
            // bucket 初始化失败直接快速失败,避免带病启动后所有对象读写持续报错
            throw new IllegalStateException("minio bucket 初始化失败: " + e.getMessage(), e);
        }
        return client;
    }

    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("created minio bucket '{}'", bucket);
        }
    }
}
