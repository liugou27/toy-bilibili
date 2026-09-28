package com.toys.video.common.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等注解:同一用户在窗口期内重复提交相同业务键的请求直接拒绝。
 * <p>场景区分:scene 作为 key 前缀(如 comment/danmaku/complete);
 * key 支持 SpEL(方法参数,如 "#videoId + ':' + #content"),自动拼接当前用户 ID。
 * <p>实现:Redis SETNX + TTL;无 Redis 的服务自动退化为直通(不拦截)。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 场景标识,作为幂等键前缀,区分不同业务。 */
    String scene();

    /** 业务键 SpEL 表达式,基于方法参数求值。 */
    String key() default "''";

    /** 幂等窗口(秒),默认 10。 */
    int windowSeconds() default 10;

    /** 命中重复时的提示文案。 */
    String message() default "操作过于频繁,请稍后再试";
}
