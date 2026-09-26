package com.toys.video.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    private static final String SECRET = "test-secret-key-32-bytes-long!abc!";
    private static final long TTL_SECONDS = 3600;

    @Test
    void issueThenParseRoundTripKeepsUserIdAndRole() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, TTL_SECONDS);
        String token = jwtUtil.issue(42L, "ADMIN");
        JwtUtil.TokenPayload payload = jwtUtil.parse(token);
        assertEquals(42L, payload.userId());
        assertEquals("ADMIN", payload.role());
    }

    @Test
    void parseTamperedTokenThrowsJwtException() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, TTL_SECONDS);
        String token = jwtUtil.issue(1L, "USER");
        // 确定性篡改:翻转 signature 首字符(避免末尾字符碰巧相同时"篡改"无效)
        int sigStart = token.lastIndexOf('.') + 1;
        char[] chars = token.toCharArray();
        chars[sigStart] = chars[sigStart] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);
        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered));
    }

    @Test
    void parseExpiredTokenThrowsJwtException() throws InterruptedException {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 1);
        String token = jwtUtil.issue(7L, "USER");
        Thread.sleep(1500);
        assertThrows(JwtException.class, () -> jwtUtil.parse(token));
    }

    @Test
    void parseBlankTokenThrowsJwtException() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, TTL_SECONDS);
        assertThrows(JwtException.class, () -> jwtUtil.parse(""));
        assertThrows(JwtException.class, () -> jwtUtil.parse("   "));
        assertThrows(JwtException.class, () -> jwtUtil.parse(null));
    }

    @Test
    void parseOverSizedTokenThrowsJwtException() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, TTL_SECONDS);
        String oversized = "a".repeat(4 * 1024 + 1);
        assertThrows(JwtException.class, () -> jwtUtil.parse(oversized));
    }
}
