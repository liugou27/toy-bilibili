package com.toys.video.video;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.toys.video.api.feign")
@SpringBootApplication
public class VideoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoApplication.class, args);
    }
}
