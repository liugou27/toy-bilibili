package com.toys.video.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.security.JwtUtil;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局鉴权过滤器:
 * - /internal/** 一律 403(内部接口只允许服务间直连,不经过网关);
 * - 白名单(/api/auth/**、GET /api/videos/**、/media/**、/actuator/**)放行;
 * - /api/admin/** 额外要求 ADMIN 角色;
 * - 其余需 Bearer JWT,校验通过后透传 X-User-Id / X-User-Role。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> WHITELIST = List.of(
            "/api/auth/**",
            "/actuator/**"
    );
    private static final List<String> PUBLIC_GET = List.of(
            "/api/videos/**",
            "/media/**"
    );

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthGlobalFilter(@Value("${toys.jwt.secret}") String secret) {
        this.jwtUtil = new JwtUtil(secret, Long.MAX_VALUE / 1000 - 1);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (path.startsWith("/internal")) {
            return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
        }

        if (isWhitelisted(request.getMethod().name(), path)) {
            return chain.filter(exchange);
        }

        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
        }
        JwtUtil.TokenPayload payload;
        try {
            payload = jwtUtil.parse(auth.substring(7));
        } catch (JwtException e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
        }

        if (path.startsWith("/api/admin") && !"ADMIN".equals(payload.role())) {
            return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
        }

        ServerHttpRequest mutated = request.mutate()
                .header(Headers.USER_ID, String.valueOf(payload.userId()))
                .header(Headers.USER_ROLE, payload.role() == null ? "" : payload.role())
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isWhitelisted(String method, String path) {
        if (WHITELIST.stream().anyMatch(p -> MATCHER.match(p, path))) {
            return true;
        }
        return "GET".equals(method) && PUBLIC_GET.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(R.fail(errorCode.getCode(), errorCode.getMessage()));
        } catch (Exception e) {
            body = "{\"code\":1001,\"message\":\"unauthorized\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
