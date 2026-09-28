package com.toys.video.moderation.feign;

import com.toys.video.common.constant.Headers;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

/** user-service 出站拦截器:透传 traceId,并标记内部调用(同 moderation/risk 的 InternalRiskFeignConfig)。 */
public class InternalUserFeignConfig implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            template.header(Headers.REQUEST_ID, traceId);
        }
        template.header(Headers.INTERNAL_CALL, "1");
    }
}
