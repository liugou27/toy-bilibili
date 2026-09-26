package com.toys.video.common.text;

import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感词过滤器:DFA 字典树逐字符匹配,用于标题/简介机审与评论/弹幕等 UGC 文本筛查。
 * 词库由调用方注入(DB 托管、热更新),key=敏感词原词,value=级别(REJECT/REVIEW)。
 * 匹配前统一归一化:NFKC → 全角转半角 → 小写 → 去除零宽字符与常见穿透分隔符;
 * 归一化只在匹配副本上进行,命中原样返回词库中的原词。
 */
@Slf4j
public class SensitiveWordFilter {

    /** 级别:REJECT 硬拒绝,REVIEW 仅记录供人审参考。 */
    public static final String LEVEL_REJECT = "REJECT";
    public static final String LEVEL_REVIEW = "REVIEW";

    /** 单次筛查每级返回的命中词上限(去重后取前 N 个)。 */
    private static final int MAX_HITS = 5;

    /** 词库快照:原词 → 级别。 */
    private final Map<String, String> words;

    private final Node root = new Node();

    /** 构建词典树;空词库允许(等待首次加载),级别缺省按 REJECT 处理。 */
    public SensitiveWordFilter(Map<String, String> words) {
        Map<String, String> safe = words == null ? Map.of() : words;
        this.words = Collections.unmodifiableMap(new LinkedHashMap<>(safe));
        int count = 0;
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            String normalized = normalize(entry.getKey());
            if (normalized.isEmpty()) {
                continue;
            }
            String level = entry.getValue() == null ? LEVEL_REJECT : entry.getValue();
            insert(normalized, entry.getKey(), level);
            count++;
        }
        log.info("sensitive word filter built: {} words", count);
    }

    /** 词库快照:原词 → 级别(不可变)。 */
    public Map<String, String> words() {
        return words;
    }

    /** 文本筛查:仅返回 REJECT 级命中(去重、按首次出现顺序,最多 5 个);null/空文本返回空列表。 */
    public List<String> screen(String text) {
        return screen(text, LEVEL_REJECT);
    }

    /** 按级别筛查:返回命中该级别的敏感词(去重、按首次出现顺序,最多 5 个)。 */
    public List<String> screen(String text, String level) {
        return List.copyOf(scan(text).getOrDefault(level, Set.of()));
    }

    /** 分级筛查:级别 → 命中词(仅含有命中的级别,各级各自按首次出现顺序去重)。 */
    public Map<String, List<String>> screenWithLevel(String text) {
        Map<String, Set<String>> byLevel = scan(text);
        Map<String, List<String>> result = new LinkedHashMap<>();
        byLevel.forEach((level, hits) -> result.put(level, List.copyOf(hits)));
        return Collections.unmodifiableMap(result);
    }

    /** 归一化扫描:NFKC → 全角转半角 → 小写 → 剔除零宽字符与穿透分隔符,再做 DFA 匹配。 */
    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c -= 0xFEE0; // 全角 ASCII 区与标点转半角
            } else if (c == 0x3000) {
                c = ' '; // 全角空格
            }
            c = Character.toLowerCase(c);
            if (c == 0x200B || c == 0x200C || c == 0x200D || c == 0xFEFF
                    || c == '.' || c == '·' || c == '-' || c == '_' || c == '*' || c == ' ') {
                continue; // 零宽字符与穿透分隔符
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** DFA 扫描:级别 → 命中词(仅含有命中的级别),命中返回词库原词。 */
    private Map<String, Set<String>> scan(String text) {
        String normalized = normalize(text);
        Map<String, Set<String>> hits = new LinkedHashMap<>();
        if (normalized.isEmpty()) {
            return hits;
        }
        for (int i = 0; i < normalized.length(); i++) {
            Node node = root;
            for (int j = i; j < normalized.length(); j++) {
                node = node.children.get(normalized.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.word != null) {
                    Set<String> set = hits.computeIfAbsent(node.level, k -> new LinkedHashSet<>());
                    if (set.size() < MAX_HITS) {
                        set.add(node.word);
                    }
                }
            }
        }
        return hits;
    }

    private void insert(String normalizedWord, String originalWord, String level) {
        Node node = root;
        for (int i = 0; i < normalizedWord.length(); i++) {
            node = node.children.computeIfAbsent(normalizedWord.charAt(i), k -> new Node());
        }
        node.word = originalWord;
        node.level = level;
    }

    /** 字典树节点:children 为后继字符分支,word 非空表示到该节点构成一个完整敏感词。 */
    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private String word;
        private String level;
    }
}
