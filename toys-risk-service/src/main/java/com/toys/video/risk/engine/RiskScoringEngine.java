package com.toys.video.risk.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.toys.video.risk.dto.RiskItem;
import com.toys.video.risk.dto.RiskRequest;
import com.toys.video.risk.dto.RiskResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 风控评级引擎:规则打分 + 分级,纯函数式(同输入同输出,无外部依赖,可独立单测)。
 *
 * 规则表(权重为可调常量):
 * - 文本 REJECT 命中:每词 40 分、文本项封顶 100,且直接满分定级 REJECT;
 * - 文本 REVIEW 命中:每词 15 分;
 * - 画面 black_ratio ≥ 0.8:60 分;<0.8 且 ≥0.3:25 分;
 * - 画面 static_suspect:20 分;无音频轨:10 分;分辨率 <480:10 分;时长越界:30 分。
 *
 * 分级:score ≥70 → REJECT;30~69 → REVIEW;<30 → PASS。
 */
@Component
public class RiskScoringEngine {

    // ==================== 规则权重(可调常量) ====================
    static final int TEXT_REJECT_PER_WORD = 40;
    static final int TEXT_REJECT_ITEM_CAP = 100;
    static final int TEXT_REVIEW_PER_WORD = 15;
    static final int BLACK_HARD_WEIGHT = 60;
    static final int BLACK_SUSPECT_WEIGHT = 25;
    static final int STATIC_SUSPECT_WEIGHT = 20;
    static final int NO_AUDIO_WEIGHT = 10;
    static final int LOW_RESOLUTION_WEIGHT = 10;
    static final int DURATION_OUT_OF_RANGE_WEIGHT = 30;

    static final double BLACK_HARD_THRESHOLD = 0.8;
    static final double BLACK_SUSPECT_THRESHOLD = 0.3;
    /** 分辨率下限(取画面高度,即 480p)。 */
    static final int RESOLUTION_FLOOR = 480;
    /** 时长合法区间 [1s, 7200s],与画面机审脚本保持一致。 */
    static final double MIN_DURATION_SEC = 1.0;
    static final double MAX_DURATION_SEC = 7200.0;

    // ==================== 分级阈值 ====================
    static final int REJECT_SCORE_THRESHOLD = 70;
    static final int REVIEW_SCORE_THRESHOLD = 30;
    static final int SCORE_CAP = 100;

    public static final String LEVEL_PASS = "PASS";
    public static final String LEVEL_REVIEW = "REVIEW";
    public static final String LEVEL_REJECT = "REJECT";

    /** 打分定级:空输入得 0 分 PASS,任何 REJECT 文本命中直接满分拒绝。 */
    public RiskResult evaluate(RiskRequest request) {
        List<String> reasons = new ArrayList<>();
        List<RiskItem> items = new ArrayList<>();

        List<String> textHits = orEmpty(request == null ? null : request.textHits());
        List<String> reviewHits = orEmpty(request == null ? null : request.reviewHits());

        // 文本 REJECT 命中:直接满分拒绝,不再叠加其余规则
        if (!textHits.isEmpty()) {
            int weight = Math.min(TEXT_REJECT_PER_WORD * textHits.size(), TEXT_REJECT_ITEM_CAP);
            String words = String.join("、", textHits);
            items.add(new RiskItem("违禁词命中", weight, "命中 " + textHits.size() + " 个拒绝级敏感词:" + words));
            reasons.add("命中违禁词:" + words);
            return new RiskResult(SCORE_CAP, LEVEL_REJECT, reasons, items);
        }

        int score = 0;

        // 文本 REVIEW 命中
        if (!reviewHits.isEmpty()) {
            String words = String.join("、", reviewHits);
            score += TEXT_REVIEW_PER_WORD * reviewHits.size();
            items.add(new RiskItem("疑似违规词命中", TEXT_REVIEW_PER_WORD * reviewHits.size(),
                    "命中 " + reviewHits.size() + " 个人审级敏感词:" + words));
            reasons.add("命中疑似违规词:" + words);
        }

        // 画面机审结果
        JsonNode screen = request == null ? null : request.screenResult();
        if (screen != null && screen.isObject()) {
            score += scoreScreen(screen, reasons, items);
        }

        score = Math.min(score, SCORE_CAP);
        return new RiskResult(score, levelFor(score), reasons, items);
    }

    /** 画面规则打分:黑帧占比、静帧、无音频、低分辨率、时长越界。 */
    private int scoreScreen(JsonNode screen, List<String> reasons, List<RiskItem> items) {
        int score = 0;

        double blackRatio = screen.path("black_ratio").asDouble(0.0);
        if (blackRatio >= BLACK_HARD_THRESHOLD) {
            score += BLACK_HARD_WEIGHT;
            items.add(new RiskItem("黑屏", BLACK_HARD_WEIGHT,
                    String.format("黑帧占比 %.2f,画面疑似黑屏", blackRatio)));
            reasons.add("画面疑似黑屏");
        } else if (blackRatio >= BLACK_SUSPECT_THRESHOLD) {
            score += BLACK_SUSPECT_WEIGHT;
            items.add(new RiskItem("黑帧偏高", BLACK_SUSPECT_WEIGHT,
                    String.format("黑帧占比 %.2f,超出可疑阈值", blackRatio)));
            reasons.add("黑帧占比偏高");
        }

        if (screen.path("static_suspect").asBoolean(false)) {
            score += STATIC_SUSPECT_WEIGHT;
            items.add(new RiskItem("静帧疑似", STATIC_SUSPECT_WEIGHT, "抽帧画面几乎无变化,疑似纯色/静帧"));
            reasons.add("画面疑似静帧或纯色");
        }

        JsonNode meta = screen.path("meta");
        if (meta.isObject()) {
            // 无音频轨:字段缺失时不计分(无法判定)
            if (meta.has("has_audio") && !meta.path("has_audio").asBoolean()) {
                score += NO_AUDIO_WEIGHT;
                items.add(new RiskItem("无音频", NO_AUDIO_WEIGHT, "视频不含音频轨"));
                reasons.add("视频无音频");
            }

            JsonNode height = meta.path("height");
            JsonNode width = meta.path("width");
            if (height.isNumber() && height.asInt() < RESOLUTION_FLOOR) {
                String resolution = (width.isNumber() ? String.valueOf(width.asInt()) : "?") + "x" + height.asInt();
                score += LOW_RESOLUTION_WEIGHT;
                items.add(new RiskItem("低分辨率", LOW_RESOLUTION_WEIGHT,
                        "分辨率 " + resolution + ",低于 " + RESOLUTION_FLOOR + "p"));
                reasons.add("视频分辨率低于" + RESOLUTION_FLOOR + "p");
            }

            JsonNode duration = meta.path("duration_sec");
            if (duration.isNumber()) {
                double d = duration.asDouble();
                if (d < MIN_DURATION_SEC || d > MAX_DURATION_SEC) {
                    score += DURATION_OUT_OF_RANGE_WEIGHT;
                    items.add(new RiskItem("时长越界", DURATION_OUT_OF_RANGE_WEIGHT,
                            String.format("时长 %.1fs,不在 [%s, %s] 秒区间", d, MIN_DURATION_SEC, MAX_DURATION_SEC)));
                    reasons.add("视频时长越界");
                }
            }
        }
        return score;
    }

    /** 分级映射:≥70 REJECT,30~69 REVIEW,<30 PASS。 */
    static String levelFor(int score) {
        if (score >= REJECT_SCORE_THRESHOLD) {
            return LEVEL_REJECT;
        }
        if (score >= REVIEW_SCORE_THRESHOLD) {
            return LEVEL_REVIEW;
        }
        return LEVEL_PASS;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
