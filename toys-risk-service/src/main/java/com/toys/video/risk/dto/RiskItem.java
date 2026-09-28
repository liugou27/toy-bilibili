package com.toys.video.risk.dto;

/** 单条风险项:命中的规则、权重与明细,供审核页逐条展示。 */
public record RiskItem(
        String name,
        int weight,
        String detail
) {
}
