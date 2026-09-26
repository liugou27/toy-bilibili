package com.toys.video.video.dto;

import java.time.LocalDateTime;

public record VideoDetail(
        Long id,
        String title,
        String description,
        String poster,
        String playbackUrl,
        Double durationSec,
        Long playCount,
        Long ownerId,
        String ownerName,
        String status,
        String note,
        String originalFilename,
        Long sizeBytes,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        /** 当前用户是否已点赞(匿名 false)。 */
        Boolean likedByMe,
        Long likeCount,
        /** 当前用户是否已收藏(匿名 false)。 */
        Boolean favoritedByMe,
        /** 我的断点续播位置(秒),仅 owner 有观看历史时返回。 */
        Double resumePosition
) {
}
