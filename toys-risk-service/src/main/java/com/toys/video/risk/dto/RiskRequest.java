package com.toys.video.risk.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 风控评级请求(moderation-service 组装)。
 *
 * @param textHits       文本机审 REJECT 级命中词
 * @param reviewHits     文本机审 REVIEW 级命中词
 * @param screenResult   画面机审结果 JSON(checks/black_ratio/static_suspect/meta),文本硬失败时为 null
 * @param avgSkinRatio   全部抽帧的平均皮肤色像素占比(0-1,画面机审未输出时为 null)
 * @param maxSkinRatio   单帧最高皮肤色像素占比(0-1,画面机审未输出时为 null)
 * @param phash          中间帧感知哈希(16 位 hex,画面机审未输出时为 null)
 * @param blacklistHits  黑样本库命中列表(汉明距离达标,moderation 预匹配)
 * @param textCategories 文本命中按违规类型汇总:key = "类型:级别"(如 "PORN:REJECT"、"VULGAR:REVIEW"),
 *                       value = 该类型该级别的命中词数;缺省时引擎按命中词列表整体归入 VULGAR 桶
 */
public record RiskRequest(
        List<String> textHits,
        List<String> reviewHits,
        JsonNode screenResult,
        Double avgSkinRatio,
        Double maxSkinRatio,
        String phash,
        List<BlacklistHit> blacklistHits,
        Map<String, Integer> textCategories
) {

    /** 兼容旧调用:仅文本命中与画面机审结果。 */
    public RiskRequest(List<String> textHits, List<String> reviewHits, JsonNode screenResult) {
        this(textHits, reviewHits, screenResult, null, null, null, null, null);
    }
}
