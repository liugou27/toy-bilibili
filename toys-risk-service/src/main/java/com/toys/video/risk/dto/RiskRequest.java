package com.toys.video.risk.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 风控评级请求(moderation-service 组装)。
 *
 * @param textHits     文本机审 REJECT 级命中词
 * @param reviewHits   文本机审 REVIEW 级命中词
 * @param screenResult 画面机审结果 JSON(checks/black_ratio/static_suspect/meta),文本硬失败时为 null
 */
public record RiskRequest(
        List<String> textHits,
        List<String> reviewHits,
        JsonNode screenResult
) {
}
