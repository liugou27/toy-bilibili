package com.toys.video.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toys.video.common.text.SensitiveWordFilter;
import com.toys.video.moderation.entity.SensitiveWord;
import com.toys.video.moderation.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 词库中心:内存持有当前 DFA 与版本号,定时检查 DB 版本热更新。
 * 版本 = 词库最大 updated_at 的 epoch millis(空表为 0);DFA 以快照整体替换,
 * 读取方始终看到「词库+版本」一致的一对。
 * 同一快照内另维护 词→分类 本地 Map(DB 同源加载),供机审把 DFA 命中词汇总为
 * 类型化文本类别(textCategories);common 的 SensitiveWordFilter 不感知分类,保持不动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    /** 违规类型:与 user-service 违规上报契约一致。 */
    public static final String TYPE_POLITIC = "POLITIC";
    public static final String TYPE_PORN = "PORN";
    public static final String TYPE_VULGAR = "VULGAR";
    public static final String TYPE_AD = "AD";

    private final SensitiveWordMapper wordMapper;

    /** 当前快照:DFA + 词→分类 + 版本号,单引用整体替换。 */
    private record Snapshot(SensitiveWordFilter filter, Map<String, String> categories, long version) {
    }

    private volatile Snapshot snapshot = new Snapshot(new SensitiveWordFilter(Map.of()), Map.of(), 0);

    /** 首次启动即加载词库。 */
    @PostConstruct
    public void initialLoad() {
        reload();
    }

    /** 当前 DFA 实例。 */
    public SensitiveWordFilter current() {
        return snapshot.filter();
    }

    /** 当前词库版本(最大 updated_at 的 epoch millis)。 */
    public long currentVersion() {
        return snapshot.version();
    }

    /** 版本与词表来自同一快照引用,避免两次 volatile 读之间发生替换导致版本/词表错配。 */
    public SnapshotView currentView() {
        Snapshot snap = snapshot;
        return new SnapshotView(snap.version(), snap.filter().words());
    }

    /** 只读视图:供快照接口一次性获取版本与词表。 */
    public record SnapshotView(long version, java.util.Map<String, String> words) {
    }

    /** 反查命中词的敏感词分类(词库未配置分类时为 null)。 */
    public String categoryOf(String word) {
        return snapshot.categories().get(word);
    }

    /**
     * 机审命中词的类型化汇总:key = "违规类型:级别"(如 "PORN:REJECT"),value = 词数。
     * 级别沿用 DFA 命中列表(REJECT/REVIEW),类型由敏感词分类映射。
     */
    public Map<String, Integer> countTextCategories(List<String> rejectHits, List<String> reviewHits) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        accumulate(counts, rejectHits, SensitiveWordFilter.LEVEL_REJECT);
        accumulate(counts, reviewHits, SensitiveWordFilter.LEVEL_REVIEW);
        return counts;
    }

    private void accumulate(Map<String, Integer> counts, List<String> words, String level) {
        if (words == null) {
            return;
        }
        for (String word : words) {
            counts.merge(violationTypeOf(categoryOf(word)) + ":" + level, 1, Integer::sum);
        }
    }

    /** 敏感词分类 → 违规类型:politic→POLITIC、porn→PORN、gamble→AD,其余(含未配置)→VULGAR;兼容中文分类名。 */
    public static String violationTypeOf(String category) {
        if (category == null || category.isBlank()) {
            return TYPE_VULGAR;
        }
        String c = category.trim().toLowerCase(Locale.ROOT);
        if (c.contains("politic") || c.contains("涉政") || c.contains("暴恐")) {
            return TYPE_POLITIC;
        }
        if (c.contains("porn") || c.contains("色情") || c.contains("情色")) {
            return TYPE_PORN;
        }
        if (c.contains("gamble") || c.contains("赌博") || c.contains("博彩")) {
            return TYPE_AD;
        }
        return TYPE_VULGAR;
    }

    /** 定时检查 DB 版本,变化才全量重载;检查失败保留当前词库。 */
    @Scheduled(fixedDelay = 30_000)
    public void reloadIfChanged() {
        try {
            long dbVersion = selectMaxVersion();
            if (dbVersion == snapshot.version()) {
                return;
            }
            reload();
        } catch (Exception e) {
            log.warn("sensitive words version check failed, keep current: {}", e.getMessage());
        }
    }

    /** 全量加载 ENABLED 词并原子替换 DFA 与词→分类 Map,版本号随 DB 最新 updated_at 前进。 */
    public void reload() {
        long dbVersion = selectMaxVersion();
        List<SensitiveWord> rows = wordMapper.selectList(new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getStatus, "ENABLED"));
        Map<String, String> words = new LinkedHashMap<>();
        Map<String, String> categories = new LinkedHashMap<>();
        for (SensitiveWord row : rows) {
            words.put(row.getWord(), row.getLevel());
            categories.put(row.getWord(), row.getCategory());
        }
        // 分类可为 null(词库未配置),用 unmodifiableMap 而非 Map.copyOf
        snapshot = new Snapshot(new SensitiveWordFilter(words),
                java.util.Collections.unmodifiableMap(categories), dbVersion);
        log.info("sensitive words loaded: {} words, version {}", words.size(), dbVersion);
    }

    private long selectMaxVersion() {
        Long version = wordMapper.selectMaxVersion();
        return version == null ? 0L : version;
    }
}
