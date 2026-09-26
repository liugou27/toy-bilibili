package com.toys.video.video.dto;

/** 分区概览:分区 key、展示名与 PUBLISHED 视频数。 */
public record CategorySummary(String key, String name, long count) {
}
