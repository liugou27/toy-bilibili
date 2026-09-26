package com.toys.video.common.text;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 敏感词过滤器:命中/不命中、空文本、大小写不敏感、去重上限、归一化穿透与分级。 */
class SensitiveWordFilterTest {

    private final SensitiveWordFilter filter = new SensitiveWordFilter(Map.of(
            "赌博", SensitiveWordFilter.LEVEL_REJECT,
            "博彩", SensitiveWordFilter.LEVEL_REJECT,
            "裸聊", SensitiveWordFilter.LEVEL_REJECT,
            "赌场", SensitiveWordFilter.LEVEL_REJECT,
            "六合彩", SensitiveWordFilter.LEVEL_REJECT,
            "网赌", SensitiveWordFilter.LEVEL_REJECT,
            "现金网", SensitiveWordFilter.LEVEL_REJECT,
            "色情", SensitiveWordFilter.LEVEL_REJECT,
            "porn", SensitiveWordFilter.LEVEL_REJECT,
            "代开发票", SensitiveWordFilter.LEVEL_REVIEW));

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

    @Test
    void screen_fullWidthAsciiNormalizes() {
        assertEquals(List.of("porn"), filter.screen("免费ｐｏｒｎ资源"));
        assertEquals(List.of("porn"), filter.screen("免费ＰＯＲＮ资源"));
    }

    @Test
    void screen_pierceSeparatorsStripped() {
        assertEquals(List.of("赌博"), filter.screen("赌.博网站"));
        assertEquals(List.of("赌博"), filter.screen("赌·博网站"));
        assertEquals(List.of("赌博"), filter.screen("赌-博网站"));
        assertEquals(List.of("赌博"), filter.screen("赌　博网站"));
    }

    @Test
    void screen_zeroWidthCharsStripped() {
        assertEquals(List.of("赌博"), filter.screen("赌\u200B博网站"));
        assertEquals(List.of("赌博"), filter.screen("赌\u200C博网站"));
        assertEquals(List.of("赌博"), filter.screen("赌\u200D博网站"));
        assertEquals(List.of("赌博"), filter.screen("赌\uFEFF博网站"));
    }

    @Test
    void screen_fullWidthPierceCombinationMatches() {
        // 全角句点 + 零宽字符混排穿透
        assertEquals(List.of("赌博"), filter.screen("赌．\u200B博网站"));
    }

    @Test
    void screen_returnsOriginalWordFromWordBook() {
        SensitiveWordFilter originalCase = new SensitiveWordFilter(Map.of("PoRn", SensitiveWordFilter.LEVEL_REJECT));
        assertEquals(List.of("PoRn"), originalCase.screen("p.o.r.n 在线"));
    }

    @Test
    void screenOnlyReturnsRejectLevel() {
        assertEquals(List.of("赌博"), filter.screen("赌博和代开发票都在文中"));
    }

    @Test
    void screenByLevelFiltersHits() {
        assertEquals(List.of("代开发票"),
                filter.screen("赌博和代开发票都在文中", SensitiveWordFilter.LEVEL_REVIEW));
        assertEquals(List.of("赌博"),
                filter.screen("赌博和代开发票都在文中", SensitiveWordFilter.LEVEL_REJECT));
        assertTrue(filter.screen("干净的文本", SensitiveWordFilter.LEVEL_REVIEW).isEmpty());
    }

    @Test
    void screenWithLevelGroupsByLevel() {
        Map<String, List<String>> grouped = filter.screenWithLevel("赌博加代开发票");
        assertEquals(Map.of(
                SensitiveWordFilter.LEVEL_REJECT, List.of("赌博"),
                SensitiveWordFilter.LEVEL_REVIEW, List.of("代开发票")), grouped);
        assertTrue(filter.screenWithLevel("红烧肉教程").isEmpty());
    }

    @Test
    void wordsReturnsUnmodifiableSnapshot() {
        assertEquals(SensitiveWordFilter.LEVEL_REJECT, filter.words().get("赌博"));
        assertEquals(SensitiveWordFilter.LEVEL_REVIEW, filter.words().get("代开发票"));
    }
}
