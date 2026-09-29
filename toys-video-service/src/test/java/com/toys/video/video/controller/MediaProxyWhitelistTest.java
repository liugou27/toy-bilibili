package com.toys.video.video.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 媒体代理白名单:段级分片放行,任意 key 枚举/目录穿越拒绝。 */
class MediaProxyWhitelistTest {

    @Test
    void allowsKnownArtifacts() {
        assertTrue(MediaProxyController.ALLOWED.matcher("123/master.m3u8").matches());
        assertTrue(MediaProxyController.ALLOWED.matcher("123/720p.m3u8").matches());
        assertTrue(MediaProxyController.ALLOWED.matcher("123/720p_0007.ts").matches());
        assertTrue(MediaProxyController.ALLOWED.matcher("123/720p_0001_0007.ts").matches());
        assertTrue(MediaProxyController.ALLOWED.matcher("123/poster.jpg").matches());
    }

    @Test
    void rejectsUnknownOrTraversal() {
        assertFalse(MediaProxyController.ALLOWED.matcher("123/480p_0001.m3u8").matches());
        assertFalse(MediaProxyController.ALLOWED.matcher("123/anything.mp4").matches());
        assertFalse(MediaProxyController.ALLOWED.matcher("123/original.bin").matches());
        assertFalse(MediaProxyController.ALLOWED.matcher("123/../other/master.m3u8").matches());
        assertFalse(MediaProxyController.ALLOWED.matcher("123/segments/0000.mp4").matches());
        assertFalse(MediaProxyController.ALLOWED.matcher("abc/master.m3u8").matches());
    }
}
