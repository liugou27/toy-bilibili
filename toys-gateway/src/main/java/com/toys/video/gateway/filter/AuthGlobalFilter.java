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
 * - Authorization 头含换行/控制字符或 token 部分含空格时一律 401(防 header 注入);
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
    /** 匿名可用的写接口:播放计数(观看者未必登录)。 */
    private static final List<String> PUBLIC_POST = List.of(
            "/api/videos/*/play"
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
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (hasIllegalAuthChars(auth)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
        }

        if (path.startsWith("/internal")) {
            return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
        }

        if (isWhitelisted(request.getMethod().name(), path)) {
            // 白名单路径:有 token 则解析并附身份(如所有者查看自己的视频),无 token 匿名放行
            ServerHttpRequest withUser = attachUserIfPresent(request);
            if (withUser != request) {
                return chain.filter(exchange.mutate().request(withUser).build());
            }
            return chain.filter(exchange);
        }

        String token = auth == null ? null : (auth.startsWith("Bearer ") ? auth.substring(7) : auth);
        if (token == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
        }
        JwtUtil.TokenPayload payload;
        try {
            payload = jwtUtil.parse(token);
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
        if ("GET".equals(method) && PUBLIC_GET.stream().anyMatch(p -> MATCHER.match(p, path))) {
            return true;
        }
        return "POST".equals(method) && PUBLIC_POST.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    /**
     * Authorization 头只接受 "Bearer <token>" 形式:出现换行、制表符或其他控制字符,
     * 或 token 部分含空格时判定为非法,直接 401(防 header 注入与日志污染)。
     */
    private boolean hasIllegalAuthChars(String auth) {
        if (auth == null) {
            return false;
        }
        for (int i = 0; i < auth.length(); i++) {
            char c = auth.charAt(i);
            if (c < 0x20) {
                return true;
            }
        }
        String token = auth.startsWith("Bearer ") ? auth.substring("Bearer ".length()) : auth;
        return token.indexOf(' ') >= 0;
    }

    /** 若携带合法 Bearer token,则返回附带身份头的请求;否则返回原请求。 */
    private ServerHttpRequest attachUserIfPresent(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return request;
        }
        try {
            JwtUtil.TokenPayload payload = jwtUtil.parse(auth.substring(7));
            return request.mutate()
                    .header(Headers.USER_ID, String.valueOf(payload.userId()))
                    .header(Headers.USER_ROLE, payload.role() == null ? "" : payload.role())
                    .build();
        } catch (JwtException e) {
            return request;
        }
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
