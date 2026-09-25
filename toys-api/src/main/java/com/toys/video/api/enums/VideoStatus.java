package com.toys.video.api.enums;

/**
 * 视频状态机。合法流转(video-service 是唯一写入方):
 * UPLOADED → AUTO_SCREENING → UNDER_REVIEW → APPROVED → TRANSCODING → PUBLISHED
 * 任意审核/转码环节 → REJECTED / TRANSCODE_FAILED(终态,可人工重置重试)。
 */
public enum VideoStatus {
    UPLOADED,
    AUTO_SCREENING,
    UNDER_REVIEW,
    APPROVED,
    TRANSCODING,
    PUBLISHED,
    REJECTED,
    TRANSCODE_FAILED
}
