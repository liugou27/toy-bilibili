package com.toys.video.common.idempotent;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等 SDK 自动装配:服务同时具备 AOP 与 Redis 时才注册切面;
 * 无 Redis 的服务(或被 component-scan 扫到但缺依赖)静默跳过,不触发类加载失败。
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({ProceedingJoinPoint.class, StringRedisTemplate.class})
@ConditionalOnBean(StringRedisTemplate.class)
public class IdempotentAutoConfiguration {

    @Bean
    public IdempotentAspect idempotentAspect() {
        return new IdempotentAspect();
    }
}
