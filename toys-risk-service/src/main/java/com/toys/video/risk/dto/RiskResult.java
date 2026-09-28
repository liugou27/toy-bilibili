package com.toys.video.risk.dto;

import java.util.List;

/**
 * 风控评级结果。
 *
 * @param score     风险分 0-100
 * @param level     评级:PASS(<30) | REVIEW(30~69) | REJECT(≥70)
 * @param reasons   人话汇总(如"命中违禁词:x"、"画面疑似黑屏")
 * @param riskItems 命中的风险项明细
 */
public record RiskResult(
        int score,
        String level,
        List<String> reasons,
        List<RiskItem> riskItems
) {
}
