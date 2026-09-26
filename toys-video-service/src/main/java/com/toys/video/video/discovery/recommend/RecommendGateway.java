package com.toys.video.video.discovery.recommend;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.toys.video.video.entity.Video;

/**
 * 推荐网关(扩展点):默认多路召回 + 融合排序实现;
 * 后续可替换为搜推算法服务实现,接口不变。
 */
public interface RecommendGateway {

    /** 匿名推荐:全站融合(热度 + 全站共现)。 */
    IPage<Video> recommend(long page, long size);

    /** 登录个性化推荐:userId 为空时退化为匿名融合。 */
    IPage<Video> recommendForUser(Long userId, long page, long size);

    /** 相关视频:共现召回为主,热度补齐;种子视频不存在或非 PUBLISHED 返回空页。 */
    IPage<Video> related(Long videoId, int size);
}
