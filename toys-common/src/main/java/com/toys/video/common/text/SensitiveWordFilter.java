package com.toys.video.common.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感词过滤器:DFA 字典树逐字符匹配,用于标题/简介机审与评论/弹幕等 UGC 文本筛查。
 * 词库 classpath sensitive-words.txt,每行一词,# 开头为注释;匹配不区分大小写。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 单次筛查返回的命中词上限(去重后取前 N 个)。 */
    private static final int MAX_HITS = 5;

    private static final String WORDS_FILE = "sensitive-words.txt";

    private final Node root = new Node();

    public SensitiveWordFilter() {
        loadWords();
    }

    /** 文本筛查:返回命中敏感词(去重、按首次出现顺序,最多 5 个);null/空文本返回空列表。 */
    public List<String> screen(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase();
        Set<String> hits = new LinkedHashSet<>();
        for (int i = 0; i < lower.length() && hits.size() < MAX_HITS; i++) {
            Node node = root;
            for (int j = i; j < lower.length(); j++) {
                node = node.children.get(lower.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.word != null && hits.size() < MAX_HITS) {
                    hits.add(node.word);
                }
            }
        }
        return new ArrayList<>(hits);
    }

    /** 构造时一次性加载词库并建树;词库缺失视为配置错误,快速失败。 */
    private void loadWords() {
        InputStream in = SensitiveWordFilter.class.getClassLoader().getResourceAsStream(WORDS_FILE);
        if (in == null) {
            throw new IllegalStateException("缺少敏感词库文件: classpath:" + WORDS_FILE);
        }
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip();
                if (word.isEmpty() || word.startsWith("#")) {
                    continue;
                }
                insert(word.toLowerCase());
                count++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("敏感词库加载失败: " + WORDS_FILE, e);
        }
        log.info("sensitive word filter loaded {} words from {}", count, WORDS_FILE);
    }

    private void insert(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), k -> new Node());
        }
        node.word = word;
    }

    /** 字典树节点:children 为后继字符分支,word 非空表示到该节点构成一个完整敏感词。 */
    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private String word;
    }
}
