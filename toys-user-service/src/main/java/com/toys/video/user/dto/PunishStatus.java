package com.toys.video.user.dto;

/** 处罚状态:muted=禁言,banned=封禁,violationCount=违规累计次数。 */
public record PunishStatus(boolean muted, boolean banned, long violationCount) {
}
