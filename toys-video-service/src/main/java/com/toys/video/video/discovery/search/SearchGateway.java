package com.toys.video.video.discovery.search;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.toys.video.video.entity.Video;

/**
 * 搜索网关(扩展点):MVP 默认 PG LIKE 实现;
 * 后续可替换为 search-service(Elasticsearch)实现,接口不变。
 */
public interface SearchGateway {

    IPage<Video> searchPublished(String keyword, long page, long size);
}
