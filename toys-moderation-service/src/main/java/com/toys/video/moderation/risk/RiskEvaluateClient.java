package com.toys.video.moderation.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * risk-service 风控评级内部契约。
 * DTO 与 risk-service 本地定义同构,按服务自治原则不进 toys-api。
 */
@FeignClient(name = "toys-risk-service", contextId = "riskEvaluateClient", path = "/internal/risk",
        configuration = InternalRiskFeignConfig.class,
        fallbackFactory = RiskEvaluateClientFallbackFactory.class)
public interface RiskEvaluateClient {

    @PostMapping("/evaluate")
    R<RiskResult> evaluate(@RequestBody RiskRequest request);

    /**
     * @param textHits     文本机审 REJECT 级命中词
     * @param reviewHits   文本机审 REVIEW 级命中词
     * @param screenResult 画面机审结果 JSON(checks/black_ratio/static_suspect/meta)
     */
    record RiskRequest(List<String> textHits, List<String> reviewHits, JsonNode screenResult) {
    }

    /**
     * @param score     风险分 0-100
     * @param level     PASS | REVIEW | REJECT
     * @param reasons   人话汇总
     * @param riskItems 风险项明细
     */
    record RiskResult(int score, String level, List<String> reasons, List<RiskItem> riskItems) {
    }

    record RiskItem(String name, int weight, String detail) {
    }
}
