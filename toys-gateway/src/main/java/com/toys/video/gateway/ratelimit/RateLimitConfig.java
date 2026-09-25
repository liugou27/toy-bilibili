package com.toys.video.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 限流维度:登录用户按 X-User-Id,匿名按客户端 IP。
 * 令牌桶参数在路由 filter 里按路由配置(auth 10/s、video 50/s、admin 20/s)。
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> keyOf(exchange);
    }

    private static Mono<String> keyOf(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return Mono.just("u:" + userId);
        }
        var remote = exchange.getRequest().getRemoteAddress();
        String ip = remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
        return Mono.just("ip:" + ip);
    }
}
