package com.toys.video.media.service;

import org.springframework.stereotype.Component;

/** 转码重试策略:重试次数判断与日志提示集中于此。 */
@Component
public class TranscodePolicy {

    /** 重试次数未用尽才允许再次尝试。 */
    public boolean shouldRetry(int attempts, int maxAttempts) {
        return attempts < maxAttempts;
    }

    /** 日志提示串:说明当前尝试进度。 */
    public String retryHint(int attempts, int maxAttempts) {
        return "已尝试 " + attempts + "/" + maxAttempts + " 次";
    }
}
