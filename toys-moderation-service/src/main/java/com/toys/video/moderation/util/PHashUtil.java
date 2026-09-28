package com.toys.video.moderation.util;

/** 感知哈希工具:hex ↔ long 互转与汉明距离,黑样本匹配共用。 */
public final class PHashUtil {

    /** 黑样本命中阈值:汉明距离 ≤ 10 视为同源画面。 */
    public static final int HAMMING_THRESHOLD = 10;

    private PHashUtil() {
    }

    /** 16 位 hex 解析为 long;非法输入(长度/字符)返回 null。 */
    public static Long fromHex(String hex) {
        if (hex == null || hex.length() != 16) {
            return null;
        }
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** long 转无符号 16 位小写 hex(与 auto_screen.py 输出格式一致)。 */
    public static String toHex(long value) {
        return String.format("%016x", value);
    }

    /** 汉明距离:两个 64bit phash 的不同位数。 */
    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
