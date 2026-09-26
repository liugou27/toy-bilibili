package com.toys.video.video.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlayCountServiceTest {

    @Test
    void clientKeyOf_usesFirstForwardedSegmentAndUserId() {
        assertEquals("1.2.3.4|42",
                PlayCountService.clientKeyOf("1.2.3.4, 10.0.0.1", "127.0.0.1", 42L));
    }

    @Test
    void clientKeyOf_trimsForwardedSegment() {
        assertEquals("1.2.3.4|42",
                PlayCountService.clientKeyOf(" 1.2.3.4 ,10.0.0.1", "127.0.0.1", 42L));
    }

    @Test
    void clientKeyOf_blankForwardedFallsBackToRemoteAddr() {
        assertEquals("127.0.0.1|42", PlayCountService.clientKeyOf("  ", "127.0.0.1", 42L));
        assertEquals("127.0.0.1|42", PlayCountService.clientKeyOf(",", "127.0.0.1", 42L));
        assertEquals("127.0.0.1|42", PlayCountService.clientKeyOf(null, "127.0.0.1", 42L));
    }

    @Test
    void clientKeyOf_anonymousUserMarkedAsAnon() {
        // XFF 首段优先语义:有效 XFF 时取首段,匿名用户追加 |anon
        assertEquals("1.2.3.4|anon",
                PlayCountService.clientKeyOf("1.2.3.4", "127.0.0.1", null));
    }

    @Test
    void md5Hex_knownVectors() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", PlayCountService.md5Hex("hello"));
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", PlayCountService.md5Hex(""));
    }

    @Test
    void md5Hex_nullTreatedAsEmpty() {
        assertEquals(PlayCountService.md5Hex(""), PlayCountService.md5Hex(null));
    }

    @Test
    void md5Hex_distinctInputsDifferAndLowercase() {
        assertNotEquals(PlayCountService.md5Hex("1.2.3.4|42"), PlayCountService.md5Hex("1.2.3.4|43"));
        String hex = PlayCountService.md5Hex("abc");
        assertEquals(hex, hex.toLowerCase());
        assertEquals(32, hex.length());
    }
}
