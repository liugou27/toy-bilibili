package com.toys.video.moderation.risk;

import com.toys.video.common.api.R;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/** risk-service 调用失败兜底:快速抛 SERVICE_UNAVAILABLE,由 ModerationService 捕获后降级为本地旧规则。 */
@Slf4j
public class RiskEvaluateClientFallbackFactory implements FallbackFactory<RiskEvaluateClient> {

    @Override
    public RiskEvaluateClient create(Throwable cause) {
        log.warn("risk-service evaluate fallback: {}", cause == null ? "unknown" : cause.getMessage());
        return request -> {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "风控服务不可用");
        };
    }
}
