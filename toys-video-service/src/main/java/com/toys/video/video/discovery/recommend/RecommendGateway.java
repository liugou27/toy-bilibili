package com.toys.video.video.discovery.recommend;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.toys.video.video.entity.Video;

/**
 * 推荐网关(扩展点):MVP 默认按发布时间排序;
 * 后续可替换为搜推算法实现(热度、协同过滤),接口不变。
 */
public interface RecommendGateway {

    IPage<Video> recommend(long page, long size);
}
