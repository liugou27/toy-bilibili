package com.toys.video.user.dto;

import java.time.LocalDateTime;

/** 关注/粉丝列表条目:对方资料 + 关注建立时间。 */
public record FollowItem(Long userId, String username, String nickname, String avatar,
                         LocalDateTime followedAt) {
}
