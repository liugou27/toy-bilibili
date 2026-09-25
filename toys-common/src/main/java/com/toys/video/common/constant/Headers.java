package com.toys.video.common.constant;

public final class Headers {
    private Headers() {
    }

    /** 网关鉴权后透传的用户身份头,下游服务只信任网关。 */
    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";
    /** 全链路追踪 ID:网关生成,Feign/MQ 透传。 */
    public static final String REQUEST_ID = "X-Request-Id";
    /** 服务间内部调用标记(Feign 拦截器携带),网关对外部请求一律拒绝该路径。 */
    public static final String INTERNAL_CALL = "X-Internal-Call";
}
