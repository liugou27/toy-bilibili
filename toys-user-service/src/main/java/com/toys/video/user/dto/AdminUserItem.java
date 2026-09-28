package com.toys.video.user.dto;

/** 后台用户列表条目:基础资料 + 违规累计与处罚态。 */
public record AdminUserItem(Long id, String username, String nickname, String role,
                            long violationCount, boolean muted, boolean banned) {
}
