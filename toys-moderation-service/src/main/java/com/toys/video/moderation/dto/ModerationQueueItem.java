package com.toys.video.moderation.dto;

import java.time.LocalDateTime;

public record ModerationQueueItem(
        Long videoId,
        String title,
        String autoVerdict,
        Long ownerId,
        String originalFilename,
        Long sizeBytes,
        Long claimedBy,
        LocalDateTime claimedAt,
        LocalDateTime createdAt
) {
}
