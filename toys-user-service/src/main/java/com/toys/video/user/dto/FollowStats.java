package com.toys.video.user.dto;

/** 关注统计:following=TA 关注的人数,follower=TA 的粉丝数。 */
public record FollowStats(long following, long follower) {
}
