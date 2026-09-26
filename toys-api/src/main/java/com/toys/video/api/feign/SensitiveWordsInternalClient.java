package com.toys.video.api.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * moderation-service 内部词库接口:其他服务拉取敏感词快照做热更新。
 * 版本号相同返回 words=null(无更新);版本号为词库最大 updated_at 的 epoch millis。
 */
@FeignClient(name = "toys-moderation-service", contextId = "sensitiveWordsInternalClient",
        path = "/internal/sensitive-words", configuration = InternalFeignConfig.class)
public interface SensitiveWordsInternalClient {

    @GetMapping("/snapshot")
    R<SensitiveSnapshot> snapshot(@RequestParam("version") long version);

    /**
     * @param version 快照版本(epoch millis)
     * @param words   原词 → 级别(REJECT/REVIEW);与请求版本一致时为 null
     */
    record SensitiveSnapshot(long version, Map<String, String> words) {
    }
}
