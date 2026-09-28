package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.entity.Video;
import com.toys.video.video.entity.VideoFavorite;
import com.toys.video.video.entity.VideoLike;
import com.toys.video.video.mapper.VideoFavoriteMapper;
import com.toys.video.video.mapper.VideoLikeMapper;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 点赞/收藏领域服务:video_likes 与 video_favorites 的唯一写入方。
 * 唯一键 (video_id, user_id) 兜底并发重复提交,重复操作静默幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionService {

    private final VideoLikeMapper likeMapper;
    private final VideoFavoriteMapper favoriteMapper;
    private final VideoMapper videoMapper;

    // ==================== 点赞 ====================

    /** 点赞缓冲键前缀:计数走 Redis,批量落库防爆款视频打挂 DB。 */
    static final String LIKE_BUFFER_PREFIX = "like:buffer:";

    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /** 点赞:关系表 INSERT(唯一约束幂等);计数只写 Redis 缓冲,由定时任务批量落库。 */
    public void like(Long videoId, Long userId) {
        requireVideo(videoId);
        VideoLike like = new VideoLike();
        like.setVideoId(videoId);
        like.setUserId(userId);
        try {
            likeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            log.info("like duplicated, video={}, user={}", videoId, userId);
            return;
        }
        bufferDelta(videoId, 1);
    }

    /** 取消点赞:删除关系;计数负向缓冲,落库时钳 0 防负数。 */
    public void unlike(Long videoId, Long userId) {
        int deleted = likeMapper.delete(new LambdaQueryWrapper<VideoLike>()
                .eq(VideoLike::getVideoId, videoId)
                .eq(VideoLike::getUserId, userId));
        if (deleted > 0) {
            bufferDelta(videoId, -1);
        }
    }

    /** 详情读取:DB 计数 + 未落库缓冲叠加(最终一致,延迟 ≤ flush 周期)。 */
    public long effectiveLikeCount(Long videoId, long dbCount) {
        try {
            String buf = redis.opsForValue().get(LIKE_BUFFER_PREFIX + videoId);
            long delta = buf == null ? 0 : Long.parseLong(buf);
            return Math.max(0, dbCount + delta);
        } catch (Exception e) {
            return Math.max(0, dbCount);
        }
    }

    private void bufferDelta(Long videoId, int delta) {
        try {
            redis.opsForValue().increment(LIKE_BUFFER_PREFIX + videoId, delta);
        } catch (Exception e) {
            // Redis 故障时回退为直接落库一次,保底不丢计数
            log.warn("like buffer failed, fallback direct update: {}", e.getMessage());
            videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                    .eq(Video::getId, videoId)
                    .setSql(delta > 0 ? "like_count = like_count + 1" : "like_count = GREATEST(like_count - 1, 0)"));
        }
    }

    /** 缓冲批量落库:读取增量→DB 条件更新→按已落库量回扣缓冲(中途新增不清零)。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void flushLikeBuffer() {
        java.util.Set<String> keys;
        try {
            keys = redis.keys(LIKE_BUFFER_PREFIX + "*");
        } catch (Exception e) {
            return;
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            try {
                String v = redis.opsForValue().get(key);
                if (v == null) {
                    continue;
                }
                long delta = Long.parseLong(v);
                if (delta == 0) {
                    continue;
                }
                long videoId = Long.parseLong(key.substring(LIKE_BUFFER_PREFIX.length()));
                videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                        .eq(Video::getId, videoId)
                        .setSql("like_count = GREATEST(like_count + " + delta + ", 0)"));
                // 只回扣本次已处理的量;期间新增的增量留给下一轮
                redis.opsForValue().increment(key, -delta);
            } catch (Exception e) {
                log.warn("flush like buffer failed for {}: {}", key, e.getMessage());
            }
        }
    }

    /** 当前用户是否已点赞(匿名返回 false)。 */
    /** 详情装配用:按 (video,user) 查点赞态,不做发布门槛(owner 预览未发布视频也需要)。 */
public boolean likedByMe(Long videoId, Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = likeMapper.selectCount(new LambdaQueryWrapper<VideoLike>()
                .eq(VideoLike::getVideoId, videoId)
                .eq(VideoLike::getUserId, userId));
        return count != null && count > 0;
    }

    // ==================== 收藏 ====================

    /** 收藏:重复收藏静默幂等。 */
    public void favorite(Long videoId, Long userId) {
        requireVideo(videoId);
        VideoFavorite favorite = new VideoFavorite();
        favorite.setVideoId(videoId);
        favorite.setUserId(userId);
        try {
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            log.info("favorite duplicated, video={}, user={}", videoId, userId);
        }
    }

    /** 取消收藏:未收藏时无操作。 */
    public void unfavorite(Long videoId, Long userId) {
        favoriteMapper.delete(new LambdaQueryWrapper<VideoFavorite>()
                .eq(VideoFavorite::getVideoId, videoId)
                .eq(VideoFavorite::getUserId, userId));
    }

    /** 当前用户是否已收藏(匿名返回 false)。 */
    /** 详情装配用:按 (video,user) 查点赞态,不做发布门槛(owner 预览未发布视频也需要)。 */
public boolean favoritedByMe(Long videoId, Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<VideoFavorite>()
                .eq(VideoFavorite::getVideoId, videoId)
                .eq(VideoFavorite::getUserId, userId));
        return count != null && count > 0;
    }

    /** 视频删除时级联清理其点赞与收藏记录。 */
    public void deleteByVideo(Long videoId) {
        likeMapper.delete(new LambdaQueryWrapper<VideoLike>()
                .eq(VideoLike::getVideoId, videoId));
        favoriteMapper.delete(new LambdaQueryWrapper<VideoFavorite>()
                .eq(VideoFavorite::getVideoId, videoId));
    }

    /** 互动前提:视频存在且已发布;未发布视频不可点赞/收藏。 */
    private void requireVideo(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        if (!VideoStatus.PUBLISHED.name().equals(video.getStatus())) {
            throw BizException.of(ErrorCode.VIDEO_NOT_PUBLISHED);
        }
    }
}
