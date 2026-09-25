package com.toys.video.media.config;

import com.toys.video.media.feign.VideoInternalClientFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Feign 兜底装配:声明 video-service 调用的 FallbackFactory。 */
@Configuration
public class FeignFallbackConfig {

    @Bean
    public VideoInternalClientFallback videoInternalClientFallback() {
        return new VideoInternalClientFallback();
    }
}
