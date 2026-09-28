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
import java.util.Map;

/**
 * 词库中心:内存持有当前 DFA 与版本号,定时检查 DB 版本热更新。
 * 版本 = 词库最大 updated_at 的 epoch millis(空表为 0);DFA 以快照整体替换,
 * 读取方始终看到「词库+版本」一致的一对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    private final SensitiveWordMapper wordMapper;

    /** 当前快照:DFA + 版本号,单引用整体替换。 */
    private record Snapshot(SensitiveWordFilter filter, long version) {
    }

    private volatile Snapshot snapshot = new Snapshot(new SensitiveWordFilter(Map.of()), 0);

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

    /** 全量加载 ENABLED 词并原子替换 DFA,版本号随 DB 最新 updated_at 前进。 */
    public void reload() {
        long dbVersion = selectMaxVersion();
        List<SensitiveWord> rows = wordMapper.selectList(new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getStatus, "ENABLED"));
        Map<String, String> words = new LinkedHashMap<>();
        for (SensitiveWord row : rows) {
            words.put(row.getWord(), row.getLevel());
        }
        snapshot = new Snapshot(new SensitiveWordFilter(words), dbVersion);
        log.info("sensitive words loaded: {} words, version {}", words.size(), dbVersion);
    }

    private long selectMaxVersion() {
        Long version = wordMapper.selectMaxVersion();
        return version == null ? 0L : version;
    }
}
