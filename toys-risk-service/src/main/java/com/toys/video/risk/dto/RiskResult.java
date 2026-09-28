package com.toys.video.risk.dto;

import java.util.List;

/**
 * 风控评级结果。
 *
 * @param score     风险分 0-100
 * @param level     评级:PASS(<30) | REVIEW(30~69) | REJECT(≥70,BLOCK 的别名,沿用旧展示语义)
 * @param reasons   人话汇总(如"命中违禁词:x"、"画面疑似黑屏")
 * @param riskItems 命中的风险项明细
 * @param action    处置动作:PASS | REVIEW | BLOCK(BLOCK 沿用旧 REJECT 语义)
 * @param violations 类型化违规项(按分降序),处置闭环取最高分项的类型
 */
public record RiskResult(
        int score,
        String level,
        List<String> reasons,
        List<RiskItem> riskItems,
        String action,
        List<Violation> violations
) {

    /** 兼容旧调用:action 由 level 映射(REJECT→BLOCK),无类型化违规项。 */
    public RiskResult(int score, String level, List<String> reasons, List<RiskItem> riskItems) {
        this(score, level, reasons, riskItems,
                "REJECT".equals(level) ? "BLOCK" : level, List.of());
    }
}
