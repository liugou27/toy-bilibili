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

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtUtil(String secret, long ttlSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwt secret must be at least 32 bytes");
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
     * @throws JwtException 签名无效或已过期
     */
    public TokenPayload parse(String token) throws JwtException {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new TokenPayload(Long.valueOf(claims.getSubject()), claims.get("role", String.class));
    }

    public record TokenPayload(Long userId, String role) {
    }
}
