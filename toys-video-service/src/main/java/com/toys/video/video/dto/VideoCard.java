package com.toys.video.video.dto;

import java.time.LocalDateTime;

public record VideoCard(
        Long id,
        String title,
        String poster,
        Double durationSec,
        Long playCount,
        Long ownerId,
        String ownerName,
        String status,
        String note,
        LocalDateTime publishedAt
) {
}
