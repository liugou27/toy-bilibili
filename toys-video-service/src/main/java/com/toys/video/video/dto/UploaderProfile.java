package com.toys.video.video.dto;

/** UP 主公开主页信息:仅统计其 PUBLISHED 视频。 */
public record UploaderProfile(Long userId, String name, String avatar, long videoCount, long totalPlayCount) {
}
