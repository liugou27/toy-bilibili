package com.toys.video.moderation.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

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
     * @param textHits       文本机审 REJECT 级命中词
     * @param reviewHits     文本机审 REVIEW 级命中词
     * @param screenResult   画面机审结果 JSON(checks/black_ratio/static_suspect/meta)
     * @param avgSkinRatio   平均皮肤色像素占比(0-1)
     * @param maxSkinRatio   最高皮肤色像素占比(0-1)
     * @param phash          中间帧感知哈希(16 位 hex)
     * @param blacklistHits  黑样本库命中列表(汉明距离达标)
     * @param textCategories 文本命中类型汇总:key = "类型:级别",value = 词数
     */
    record RiskRequest(List<String> textHits, List<String> reviewHits, JsonNode screenResult,
                       Double avgSkinRatio, Double maxSkinRatio, String phash,
                       List<BlacklistHit> blacklistHits, Map<String, Integer> textCategories) {
    }

    /**
     * @param score     风险分 0-100
     * @param level     PASS | REVIEW | REJECT(旧展示语义)
     * @param reasons   人话汇总
     * @param riskItems 风险项明细
     * @param action    PASS | REVIEW | BLOCK
     * @param violations 类型化违规项(按分降序)
     */
    record RiskResult(int score, String level, List<String> reasons, List<RiskItem> riskItems,
                      String action, List<Violation> violations) {
    }

    record RiskItem(String name, int weight, String detail) {
    }

    /** 黑样本命中:type 为违规类型,phash 为样本哈希,distance 为汉明距离。 */
    record BlacklistHit(String type, String phash, int distance) {
    }

    /** 类型化违规项:处置闭环取最高分项的类型。 */
    record Violation(String type, int score, String evidence) {
    }
}
