package com.toys.video.media.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/** 实例标识:主机名+随机后缀,启动时生成,用于转码作业的持有与围栏。 */
@Component
public class InstanceId {

    private final String value;

    public InstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        if (host.length() > 24) {
            host = host.substring(0, 24);
        }
        this.value = host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String value() {
        return value;
    }
}
