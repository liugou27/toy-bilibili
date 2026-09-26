package com.toys.video.moderation.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 敏感词过滤器:词库加载、命中/不命中、空文本、大小写不敏感与去重上限。 */
class SensitiveWordFilterTest {

    private final SensitiveWordFilter filter = new SensitiveWordFilter();

    @Test
    void screen_hitsWordsInFirstSeenOrder() {
        List<String> hits = filter.screen("这是一段包含赌博和裸聊的违规简介");
        assertEquals(List.of("赌博", "裸聊"), hits);
    }

    @Test
    void screen_cleanTextReturnsEmpty() {
        assertTrue(filter.screen("今天教大家做红烧肉,记得多备三碗米饭").isEmpty());
    }

    @Test
    void screen_nullOrBlankTextReturnsEmpty() {
        assertTrue(filter.screen(null).isEmpty());
        assertTrue(filter.screen("").isEmpty());
        assertTrue(filter.screen("   \n  ").isEmpty());
    }

    @Test
    void screen_mixedCaseEnglishWordMatches() {
        assertEquals(List.of("porn"), filter.screen("Watch free PoRn videos here"));
    }

    @Test
    void screen_dedupAndCapsAtFiveHits() {
        List<String> hits = filter.screen("赌博、博彩、赌博、赌场、六合彩、网赌、现金网、色情");
        assertEquals(List.of("赌博", "博彩", "赌场", "六合彩", "网赌"), hits);
    }

    @Test
    void screen_wordMatchInsideSentence() {
        List<String> hits = filter.screen("宣传赌博网站的链接");
        assertEquals(List.of("赌博"), hits);
    }
}
