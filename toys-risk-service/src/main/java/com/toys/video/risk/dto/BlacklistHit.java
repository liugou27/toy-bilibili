package com.toys.video.risk.dto;

/** 黑样本命中:画面 phash 与黑样本库样本的汉明距离达到阈值。 */
public record BlacklistHit(
        String type,
        String phash,
        int distance
) {
}
