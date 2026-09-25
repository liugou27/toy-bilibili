package com.toys.video.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** JWT 签发与校验(HS256)。user-service 签发,网关与各服务校验。 */
public class JwtUtil {

    /** token 长度上限:正常 JWT 远小于 4KB,超长输入直接拒绝,避免无谓的签名运算。 */
    private static final int MAX_TOKEN_LENGTH = 4 * 1024;

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtUtil(String secret, long ttlSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwt secret must be at least 32 bytes");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("jwt ttl-seconds must be positive");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(long userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验签名与有效期,返回载荷。
     *
     * @throws JwtException token 为空/超长、签名无效或已过期
     */
    public TokenPayload parse(String token) throws JwtException {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new JwtException("invalid token");
        }
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new TokenPayload(Long.valueOf(claims.getSubject()), claims.get("role", String.class));
    }

    public record TokenPayload(Long userId, String role) {
    }
}
