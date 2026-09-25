package com.toys.video.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.toys.video.api.feign")
@SpringBootApplication
public class MediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}
