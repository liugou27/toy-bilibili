package com.toys.video.video.discovery.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 默认实现:PG 标题 LIKE,按发布时间倒序。 */
@Component
@RequiredArgsConstructor
public class PgLikeSearchGateway implements SearchGateway {

    private final VideoMapper videoMapper;

    @Override
    public IPage<Video> searchPublished(String keyword, long page, long size) {
        LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<Video>()
                .eq(Video::getStatus, VideoStatus.PUBLISHED.name());
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Video::getTitle, keyword.trim());
        }
        wrapper.orderByDesc(Video::getPublishedAt);
        return videoMapper.selectPage(new Page<>(page, size), wrapper);
    }
}
