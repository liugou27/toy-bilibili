package com.toys.video.api.feign;

import com.toys.video.common.constant.Headers;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

/** Feign 出站:透传 traceId,并标记内部调用。 */
public class InternalFeignConfig implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            template.header(Headers.REQUEST_ID, traceId);
        }
        template.header(Headers.INTERNAL_CALL, "1");
    }
}
