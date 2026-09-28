package com.toys.video.moderation.risk;

import com.toys.video.common.constant.Headers;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

/** risk-service 出站拦截器:透传 traceId,并标记内部调用(同 toys-api 的 InternalFeignConfig)。 */
public class InternalRiskFeignConfig implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            template.header(Headers.REQUEST_ID, traceId);
        }
        template.header(Headers.INTERNAL_CALL, "1");
    }
}
