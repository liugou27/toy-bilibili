package com.toys.video.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {

    @Test
    void notAdded_isNeverContained() {
        BloomFilter filter = new BloomFilter(1000, 0.01);
        for (int i = 0; i < 1000; i++) {
            assertFalse(filter.mightContain("missing-" + i));
        }
        assertEquals(0.0, filter.saturation());
        assertEquals(1000, filter.expectedInsertions());
    }

    @Test
    void added_isAlwaysContained() {
        BloomFilter filter = new BloomFilter(10_000, 0.01);
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            members.add("user_" + i);
        }
        members.forEach(filter::add);
        for (String member : members) {
            assertTrue(filter.mightContain(member), "添加过的元素不得判定为不存在: " + member);
        }
    }

    @Test
    void saturation_growsWithInsertions() {
        BloomFilter filter = new BloomFilter(100_000, 0.01);
        for (int i = 0; i < 1_000; i++) {
            filter.add("s-" + i);
        }
        double early = filter.saturation();
        for (int i = 1_000; i < 50_000; i++) {
            filter.add("s-" + i);
        }
        double late = filter.saturation();
        assertTrue(late > early, "饱和度须随插入增长: " + early + " -> " + late);
        assertTrue(late < 1.0);
    }

    @Test
    void falsePositiveRate_staysBelowThreePercent() {
        int n = 10_000;
        BloomFilter filter = new BloomFilter(n, 0.01);
        for (int i = 0; i < n; i++) {
            filter.add("member-" + i);
        }
        int probes = 10 * n;
        int falsePositives = 0;
        for (int i = 0; i < probes; i++) {
            if (filter.mightContain("probe-" + i)) {
                falsePositives++;
            }
        }
        double rate = (double) falsePositives / probes;
        assertTrue(rate < 0.03, "实测误判率须低于 3%,实际 " + rate);
    }

    @Test
    void insertions_countsRepeatedAdds() {
        BloomFilter filter = new BloomFilter(1000, 0.01);
        filter.add("a");
        filter.add("a");
        filter.add("b");
        assertEquals(3, filter.insertions());
    }

    @Test
    void constructor_rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(-1, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(100, 1.0));
    }
}
