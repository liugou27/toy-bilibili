package com.toys.video.common.util;

import java.nio.charset.StandardCharsets;

/**
 * 自研布隆过滤器:FNV-1a 64 位双种子哈希 + long[] 位数组,不引第三方依赖。
 * <p>
 * mightContain 返回 false 时元素必然不存在,返回 true 时可能误判(约 falsePositiveRate);
 * 实例方法均加锁,可并发调用。
 */
public class BloomFilter {

    /** FNV-1a 64 位标准偏移基数,作为第一个哈希的种子。 */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    /** 第二个哈希的种子:偏移基数字节重排,与第一个种子产生独立的哈希序列。 */
    private static final long SECOND_SEED = 0x84222325cbf29ce4L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final long[] bits;
    private final int numBits;
    private final int numHashFunctions;
    private final long expectedInsertions;
    /** 已 add 的元素个数(重复 add 也计数)。 */
    private long insertions;

    public BloomFilter(long expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive: " + expectedInsertions);
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0,1): " + falsePositiveRate);
        }
        double ln2 = Math.log(2);
        // 最优位数 m = ceil(-n·ln p / (ln 2)^2)
        long bitsCount = (long) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (ln2 * ln2));
        if (bitsCount > Integer.MAX_VALUE - 63L) {
            throw new IllegalArgumentException("expectedInsertions too large: " + expectedInsertions);
        }
        this.expectedInsertions = expectedInsertions;
        this.numBits = (int) bitsCount;
        // 最优哈希个数 k = max(1, round(m / n · ln 2))
        this.numHashFunctions = Math.max(1, (int) Math.round((double) numBits / expectedInsertions * ln2));
        this.bits = new long[(int) (((long) numBits + 63) >>> 6)];
    }

    /** 加入元素;重复加入只多计数,位数组幂等。 */
    public synchronized void add(String value) {
        long[] hash = hashes(value);
        for (int i = 0; i < numHashFunctions; i++) {
            setBit(Long.remainderUnsigned(hash[0] + (long) i * hash[1], numBits));
        }
        insertions++;
    }

    /** 可能包含返回 true(可能误判);返回 false 时必然未加入过。 */
    public synchronized boolean mightContain(String value) {
        long[] hash = hashes(value);
        for (int i = 0; i < numHashFunctions; i++) {
            if (!getBit(Long.remainderUnsigned(hash[0] + (long) i * hash[1], numBits))) {
                return false;
            }
        }
        return true;
    }

    /** 饱和度:位数组中已置位比例,随插入增长,1.0 表示全满(此时误判率不可控)。 */
    public synchronized double saturation() {
        long setBits = 0;
        for (long word : bits) {
            setBits += Long.bitCount(word);
        }
        return (double) setBits / numBits;
    }

    /** 构造时声明的期望插入量。 */
    public synchronized long expectedInsertions() {
        return expectedInsertions;
    }

    /** 已加入元素个数(含重复 add)。 */
    public synchronized long insertions() {
        return insertions;
    }

    /**
     * 双种子 FNV-1a 64:返回 {h1, |h2|},第 i 个哈希 = h1 + i·|h2|。
     * |h2| 为 0 时退化为 1,保证各次哈希仍落在不同位置。
     */
    private long[] hashes(String value) {
        long h1 = fnv1a64(value, FNV_OFFSET_BASIS);
        long h2 = Math.abs(fnv1a64(value, SECOND_SEED));
        return new long[]{h1, h2 == 0 ? 1 : h2};
    }

    /** FNV-1a 64:种子作为初始偏移量,逐字节异或后乘素数。 */
    private static long fnv1a64(String value, long seed) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        long hash = seed;
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private void setBit(long bitIndex) {
        bits[(int) (bitIndex >>> 6)] |= 1L << (bitIndex & 63);
    }

    private boolean getBit(long bitIndex) {
        return (bits[(int) (bitIndex >>> 6)] & (1L << (bitIndex & 63))) != 0;
    }
}
