package com.toys.video.common.idempotent;

import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.time.Duration;

/**
 * 幂等切面:SETNX 抢占窗口键,抢占失败视为重复提交。
 * Redis 不可用或服务未依赖 Redis 时直通(由 DB 唯一约束等兜底),不阻断业务。
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class IdempotentAspect {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        if (redis == null) {
            return pjp.proceed();
        }
        String bizKey = evaluateKey(pjp, idempotent.key());
        Long uid = UserContext.userId();
        String key = "idem:" + idempotent.scene() + ":" + (uid == null ? "anon" : uid) + ":" + bizKey;
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(idempotent.windowSeconds()));
        } catch (Exception e) {
            log.warn("idempotent redis unavailable, pass through: {}", e.getMessage());
            return pjp.proceed();
        }
        if (Boolean.FALSE.equals(first)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, idempotent.message());
        }
        return pjp.proceed();
    }

    /** SpEL 基于方法参数求值;求值失败回退为参数数组哈希,保证键稳定。 */
    private String evaluateKey(ProceedingJoinPoint pjp, String spel) {
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            var ctx = new MethodBasedEvaluationContext(
                    pjp.getTarget(), sig.getMethod(), pjp.getArgs(), discoverer);
            Object v = parser.parseExpression(spel).getValue(ctx);
            return String.valueOf(v);
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.deepHashCode(pjp.getArgs()));
        }
    }
}
