package com.toys.video.api.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FallbackFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * user-service 调用失败兜底:展示名缺失可降级,页面已有"用户{id}"兜底展示。
 */
@Slf4j
public class UserInternalClientFallbackFactory implements FallbackFactory<UserInternalClient> {

    @Override
    public UserInternalClient create(Throwable cause) {
        return new UserInternalClient() {
            @Override
            public R<List<UserBrief>> batch(List<Long> ids) {
                log.warn("user-service batch fallback: {}", cause.getMessage());
                return R.ok(List.of());
            }

            @Override
            public R<PunishStatus> punish(Long id) {
                log.warn("user-service punish fallback: {}", cause.getMessage());
                return R.ok(new PunishStatus(false, false, 0));
            }

            @Override
            public R<PunishStatus> reportViolation(ViolationReport report) {
                log.warn("user-service reportViolation fallback: {}", cause.getMessage());
                return R.ok(new PunishStatus(false, false, 0));
            }
        };
    }
}
