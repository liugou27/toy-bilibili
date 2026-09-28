package com.toys.video.risk.dto;

/** 类型化违规项:处置闭环按最高分违规的类型上报 user-service。 */
public record Violation(
        String type,
        int score,
        String evidence
) {
}
