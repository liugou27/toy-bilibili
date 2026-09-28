package com.toys.video.moderation.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * user-service 违规上报内部契约:机审拒绝时记用户违规(计数/禁言/封禁由 user-service 裁决)。
 * 接口由 user-service 实现;调用方必须 try/catch,失败仅告警不阻断审核主流程。
 */
@FeignClient(name = "toys-user-service", contextId = "userPunishClient", path = "/internal/users",
        configuration = InternalUserFeignConfig.class)
public interface UserPunishClient {

    @PostMapping("/violations")
    R<PunishResult> reportViolation(@RequestBody ViolationReport report);

    /**
     * @param userId  违规用户
     * @param type    POLITIC | PORN | VULGAR | AD | OTHER
     * @param reason  违规证据汇总
     * @param videoId 关联视频,可空
     */
    record ViolationReport(Long userId, String type, String reason, Long videoId) {
    }

    /** @param violationCount 累计违规次数 @param muted 是否已禁言 @param banned 是否已封禁 */
    record PunishResult(int violationCount, boolean muted, boolean banned) {
    }
}
