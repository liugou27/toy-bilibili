package com.toys.video.video.service;

import com.toys.video.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VideoMetaPolicyTest {

    // ==================== category ====================

    @Test
    void normalizeCategory_nullOrBlankReturnsNull() {
        assertNull(VideoMetaPolicy.normalizeCategory(null));
        assertNull(VideoMetaPolicy.normalizeCategory(""));
        assertNull(VideoMetaPolicy.normalizeCategory("   "));
    }

    @Test
    void normalizeCategory_validKeyReturnsTrimmed() {
        assertEquals("anime", VideoMetaPolicy.normalizeCategory("anime"));
        assertEquals("anime", VideoMetaPolicy.normalizeCategory(" anime "));
    }

    @Test
    void normalizeCategory_unknownKeyThrows() {
        assertThrows(BizException.class, () -> VideoMetaPolicy.normalizeCategory("movie"));
        assertThrows(BizException.class, () -> VideoMetaPolicy.normalizeCategory("ANIME"));
    }

    // ==================== normalizeTags ====================

    @Test
    void normalizeTags_nullOrBlankReturnsEmptyString() {
        assertEquals("", VideoMetaPolicy.normalizeTags(null));
        assertEquals("", VideoMetaPolicy.normalizeTags("   "));
    }

    @Test
    void normalizeTags_splitsOnWhitespaceAndCommaJoinsWithComma() {
        assertEquals("a,b,c,d", VideoMetaPolicy.normalizeTags("a b,c， d"));
        assertEquals("美食,探店", VideoMetaPolicy.normalizeTags("美食  探店"));
    }

    @Test
    void normalizeTags_fiveTagsOk() {
        assertEquals("1,2,3,4,5", VideoMetaPolicy.normalizeTags("1,2 3 4 5"));
    }

    @Test
    void normalizeTags_moreThanFiveThrows() {
        assertThrows(BizException.class, () -> VideoMetaPolicy.normalizeTags("1 2 3 4 5 6"));
    }

    @Test
    void normalizeTags_sixteenCharTagOk() {
        String tag = "一二三四五六七八九十一二三四五六";
        assertEquals(16, tag.length());
        assertEquals(tag, VideoMetaPolicy.normalizeTags(tag));
    }

    @Test
    void normalizeTags_overSixteenCharThrows() {
        assertThrows(BizException.class,
                () -> VideoMetaPolicy.normalizeTags("一二三四五六七八九十一二三四五六七"));
    }

    // ==================== parseTags ====================

    @Test
    void parseTags_nullOrBlankReturnsEmptyList() {
        assertEquals(List.of(), VideoMetaPolicy.parseTags(null));
        assertEquals(List.of(), VideoMetaPolicy.parseTags(""));
        assertEquals(List.of(), VideoMetaPolicy.parseTags("  "));
    }

    @Test
    void parseTags_splitsStoredCommaString() {
        assertEquals(List.of("a", "b"), VideoMetaPolicy.parseTags("a,b"));
    }
}
