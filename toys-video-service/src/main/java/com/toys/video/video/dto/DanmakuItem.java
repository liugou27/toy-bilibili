package com.toys.video.video.dto;

/** 弹幕项:timeSec 为视频内出现位置(秒)。 */
public record DanmakuItem(
        Long id,
        Double timeSec,
        String content
) {
}
