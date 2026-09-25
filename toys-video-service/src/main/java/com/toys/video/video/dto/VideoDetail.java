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
        LocalDateTime publishedAt
) {
}
