package com.toys.video.video.dto;

import java.time.LocalDateTime;
import java.util.List;

public record VideoDetail(
        Long id,
        String title,
        String description,
        String poster,
        String playbackUrl,
        Double durationSec,
        long playCount,
        Long ownerId,
        String ownerName,
        String status,
        String note,
        /** 分区 key,未设置时为 null。 */
        String category,
        /** 标签列表,存储为逗号串。 */
        List<String> tags,
        String originalFilename,
        Long sizeBytes,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        /** 当前用户是否已点赞(匿名 false)。 */
        Boolean likedByMe,
        long likeCount,
        /** 当前用户是否已收藏(匿名 false)。 */
        Boolean favoritedByMe,
        /** 我的断点续播位置(秒),仅 owner 有观看历史时返回。 */
        Double resumePosition
) {
}
