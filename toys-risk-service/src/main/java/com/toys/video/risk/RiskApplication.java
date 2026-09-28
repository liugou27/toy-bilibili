package com.toys.video.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/** 风控评级服务:无状态纯计算,无数据库,仅服务间调用。 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.toys.video")
public class RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
