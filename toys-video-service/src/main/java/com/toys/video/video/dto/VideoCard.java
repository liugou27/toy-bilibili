package com.toys.video.video.dto;

import java.time.LocalDateTime;

public record VideoCard(
        Long id,
        String title,
        String poster,
        Double durationSec,
        long playCount,
        Long ownerId,
        String ownerName,
        String status,
        String note,
        /** 分区 key,未设置时为 null。 */
        String category,
        /** 标签,逗号分隔原样返回。 */
        String tags,
        LocalDateTime publishedAt,
        /** 我的断点续播位置(秒),仅播放历史接口填充。 */
        Double positionSec
) {

    /** 常规列表卡片:无断点位置。 */
    public VideoCard(Long id, String title, String poster, Double durationSec, long playCount,
                     Long ownerId, String ownerName, String status, String note,
                     String category, String tags, LocalDateTime publishedAt) {
        this(id, title, poster, durationSec, playCount, ownerId, ownerName, status, note,
                category, tags, publishedAt, null);
    }
}
