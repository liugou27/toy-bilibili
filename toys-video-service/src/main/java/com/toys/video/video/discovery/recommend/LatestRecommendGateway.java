package com.toys.video.video.discovery.recommend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 默认实现:最新发布优先。 */
@Component
@RequiredArgsConstructor
public class LatestRecommendGateway implements RecommendGateway {

    private final VideoMapper videoMapper;

    @Override
    public IPage<Video> recommend(long page, long size) {
        return videoMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getStatus, VideoStatus.PUBLISHED.name())
                        .orderByDesc(Video::getPublishedAt));
    }
}
