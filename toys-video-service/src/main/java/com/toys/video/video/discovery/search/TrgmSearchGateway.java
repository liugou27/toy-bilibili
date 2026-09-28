package com.toys.video.video.discovery.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 默认实现:PG pg_trgm 标题搜索,相似度 + 播放量排序;
 * pg_trgm 不可用(扩展未装/版本过低)时熔断降级为 LIKE,每 5 分钟复位重探,恢复后自动切回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrgmSearchGateway implements SearchGateway {

    /** trgm 熔断标志:置位后直接走 LIKE,避免每次请求都撞一次 trgm 异常。 */
    private volatile boolean trgmBroken = false;

    private final VideoMapper videoMapper;

    @Override
    public IPage<Video> searchPublished(String keyword, long page, long size) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return latest(page, size);
        }
        if (trgmBroken) {
            return likeSearch(kw, page, size);
        }
        try {
            return videoMapper.searchByTrgm(new Page<>(page, size), kw);
        } catch (RuntimeException e) {
            log.warn("trgm search unavailable, fall back to like: {}", e.getMessage());
            trgmBroken = true;
            return likeSearch(kw, page, size);
        }
    }

    /** 定期复位熔断标志,让 pg_trgm 恢复后自动重试。 */
    @Scheduled(fixedDelay = 300_000)
    public void resetTrgmBroken() {
        if (trgmBroken) {
            trgmBroken = false;
            log.info("trgm breaker reset, next search will retry pg_trgm");
        }
    }

    /** pg_trgm 降级:标题 LIKE 子串匹配,发布时间倒序。 */
    private IPage<Video> likeSearch(String keyword, long page, long size) {
        return videoMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getStatus, VideoStatus.PUBLISHED.name())
                        .like(Video::getTitle, keyword)
                        .orderByDesc(Video::getPublishedAt));
    }

    private IPage<Video> latest(long page, long size) {
        return videoMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getStatus, VideoStatus.PUBLISHED.name())
                        .orderByDesc(Video::getPublishedAt));
    }
}
