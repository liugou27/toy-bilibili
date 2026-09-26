package com.toys.video.video.discovery.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认实现:PG pg_trgm 标题搜索,相似度 + 播放量排序;
 * pg_trgm 不可用(扩展未装/版本过低)时降级为 LIKE,按发布时间倒序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrgmSearchGateway implements SearchGateway {

    private final VideoMapper videoMapper;

    @Override
    public IPage<Video> searchPublished(String keyword, long page, long size) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return latest(page, size);
        }
        try {
            return videoMapper.searchByTrgm(new Page<>(page, size), kw);
        } catch (RuntimeException e) {
            log.warn("trgm search unavailable, fall back to like: {}", e.getMessage());
            return likeSearch(kw, page, size);
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
