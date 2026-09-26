package com.toys.video.video.text;

import com.toys.video.api.feign.SensitiveWordsInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.text.SensitiveWordFilter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 敏感词热更新持有者:启动从 moderation-service 拉取一次词库快照(失败保持空词库,不阻断启动),
 * 之后每 30 秒带本地版本比对,版本变化才原子替换 DFA;Feign 失败保留本地词库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordHolder {

    private final SensitiveWordsInternalClient sensitiveWordsClient;

    private volatile SensitiveWordFilter filter = new SensitiveWordFilter(Map.of());

    private volatile long version = 0;

    /** 启动加载一次。 */
    @PostConstruct
    public void initialLoad() {
        refresh();
    }

    /** 定时拉取快照:版本一致时服务端返回 words=null,不替换。 */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        try {
            R<SensitiveWordsInternalClient.SensitiveSnapshot> resp = sensitiveWordsClient.snapshot(version);
            if (resp == null || !resp.isSuccess() || resp.data() == null) {
                log.warn("sensitive words snapshot unavailable, keep local version {}", version);
                return;
            }
            SensitiveWordsInternalClient.SensitiveSnapshot snap = resp.data();
            if (snap.words() == null) {
                return;
            }
            filter = new SensitiveWordFilter(snap.words());
            version = snap.version();
            log.info("sensitive words updated: version {} ({} words)", version, snap.words().size());
        } catch (Exception e) {
            log.warn("sensitive words refresh failed, keep local version {}: {}", version, e.getMessage());
        }
    }

    /** 当前 DFA 实例。 */
    public SensitiveWordFilter current() {
        return filter;
    }
}
