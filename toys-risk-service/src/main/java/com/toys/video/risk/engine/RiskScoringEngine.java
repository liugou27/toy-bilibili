package com.toys.video.risk.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.toys.video.risk.dto.BlacklistHit;
import com.toys.video.risk.dto.RiskItem;
import com.toys.video.risk.dto.RiskRequest;
import com.toys.video.risk.dto.RiskResult;
import com.toys.video.risk.dto.Violation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控评级引擎:规则打分 + 违规类型化 + 分级,纯函数式(同输入同输出,无外部依赖,可独立单测)。
 *
 * 规则表(权重为可调常量,分值按违规类型分桶累计,桶与总分均封顶 100):
 * - 文本:按敏感词类别分桶(politic→POLITIC、porn→PORN、gamble→AD、其余→VULGAR),
 *   REJECT 词每词 40 分、REVIEW 词每词 15 分,桶内封顶;任一 REJECT 命中沿用旧语义直接满分拒绝;
 * - 皮肤占比:avg>0.45 或 max>0.6 → PORN +70;0.30≤avg≤0.45 → PORN +35;
 * - 黑样本:phash 汉明距离命中的各类型各 +80(同类型多命中只计一次);
 * - 画面 black_ratio ≥ 0.8:60 分;<0.8 且 ≥0.3:25 分;
 * - 画面 static_suspect:20 分;无音频轨:10 分;分辨率 <480:10 分;时长越界:30 分(归 OTHER 桶)。
 *
 * 分级:score ≥70 → action=BLOCK(沿用 REJECT 语义);30~69 → REVIEW;<30 → PASS。
 * violations 按分降序,处置闭环取最高分项的类型。
 */
@Component
public class RiskScoringEngine {

    // ==================== 违规类型与处置动作 ====================
    public static final String TYPE_POLITIC = "POLITIC";
    public static final String TYPE_PORN = "PORN";
    public static final String TYPE_VULGAR = "VULGAR";
    public static final String TYPE_AD = "AD";
    public static final String TYPE_OTHER = "OTHER";

    public static final String ACTION_PASS = "PASS";
    public static final String ACTION_REVIEW = "REVIEW";
    public static final String ACTION_BLOCK = "BLOCK";

    // ==================== 规则权重(可调常量) ====================
    static final int TEXT_REJECT_PER_WORD = 40;
    static final int TEXT_REVIEW_PER_WORD = 15;
    /** 文本类型桶封顶(与旧文本项封顶一致)。 */
    static final int TEXT_BUCKET_CAP = 100;
    static final int SKIN_HARD_WEIGHT = 70;
    static final int SKIN_SUSPECT_WEIGHT = 35;
    static final int BLACKLIST_HIT_WEIGHT = 80;
    static final int BLACK_HARD_WEIGHT = 60;
    static final int BLACK_SUSPECT_WEIGHT = 25;
    static final int STATIC_SUSPECT_WEIGHT = 20;
    static final int NO_AUDIO_WEIGHT = 10;
    static final int LOW_RESOLUTION_WEIGHT = 10;
    static final int DURATION_OUT_OF_RANGE_WEIGHT = 30;

    // ==================== 规则阈值 ====================
    static final double SKIN_HARD_AVG_THRESHOLD = 0.45;
    static final double SKIN_HARD_MAX_THRESHOLD = 0.6;
    static final double SKIN_SUSPECT_AVG_THRESHOLD = 0.30;
    static final double BLACK_HARD_THRESHOLD = 0.8;
    static final double BLACK_SUSPECT_THRESHOLD = 0.3;
    /** 分辨率下限(取画面高度,即 480p)。 */
    static final int RESOLUTION_FLOOR = 480;
    /** 时长合法区间 [1s, 7200s],与画面机审脚本保持一致。 */
    static final double MIN_DURATION_SEC = 1.0;
    static final double MAX_DURATION_SEC = 7200.0;

    // ==================== 分级阈值 ====================
    static final int BLOCK_SCORE_THRESHOLD = 70;
    static final int REVIEW_SCORE_THRESHOLD = 30;
    static final int SCORE_CAP = 100;

    public static final String LEVEL_PASS = "PASS";
    public static final String LEVEL_REVIEW = "REVIEW";
    public static final String LEVEL_REJECT = "REJECT";

    /** 打分定级:空输入得 0 分 PASS,任何 REJECT 文本命中直接满分拒绝。 */
    public RiskResult evaluate(RiskRequest request) {
        List<String> reasons = new ArrayList<>();
        List<RiskItem> items = new ArrayList<>();
        Buckets buckets = new Buckets();

        List<String> textHits = orEmpty(request == null ? null : request.textHits());
        List<String> reviewHits = orEmpty(request == null ? null : request.reviewHits());

        // 文本 REJECT 命中:沿用旧语义直接满分拒绝,不再叠加其余规则
        if (scoreTextBuckets(request, textHits, reviewHits, reasons, items, buckets)) {
            return finish(SCORE_CAP, reasons, items, buckets);
        }

        // 皮肤占比(色情)与黑样本命中
        scoreSkin(request, reasons, items, buckets);
        scoreBlacklist(request, reasons, items, buckets);

        // 画面机审结果(不类型化,归 OTHER 桶)
        JsonNode screen = request == null ? null : request.screenResult();
        if (screen != null && screen.isObject()) {
            scoreScreen(screen, reasons, items, buckets);
        }

        return finish(buckets.total(), reasons, items, buckets);
    }

    // ==================== 文本:按类型分桶 ====================

    /**
     * 文本命中按类型累计:textCategories 的 key 形如 "类型:级别"(如 "PORN:REJECT"),
     * value 为该类型该级别命中词数;类别汇总缺省时按命中词列表整体归 VULGAR 桶。
     *
     * @return 是否存在 REJECT 级命中(调用方据此直接满分拒绝)
     */
    private boolean scoreTextBuckets(RiskRequest request, List<String> textHits, List<String> reviewHits,
                                     List<String> reasons, List<RiskItem> items, Buckets buckets) {
        Map<String, Integer> rejectByType = new LinkedHashMap<>();
        Map<String, Integer> reviewByType = new LinkedHashMap<>();
        Map<String, Integer> categories = request == null || request.textCategories() == null
                ? Map.of() : request.textCategories();
        for (Map.Entry<String, Integer> e : categories.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey();
            int count = e.getValue() == null ? 0 : e.getValue();
            if (count <= 0) {
                continue;
            }
            int sep = key.indexOf(':');
            String type = normalizeType(sep > 0 ? key.substring(0, sep) : key);
            String level = sep > 0 ? key.substring(sep + 1) : "";
            // 未知级别按低权重的 REVIEW 处理,避免脏数据放大分值
            if (LEVEL_REJECT.equals(level)) {
                rejectByType.merge(type, count, Integer::sum);
            } else {
                reviewByType.merge(type, count, Integer::sum);
            }
        }
        // 兜底:调用方未送类别汇总时,REJECT 命中词整体归 VULGAR 桶
        if (rejectByType.isEmpty() && !textHits.isEmpty()) {
            rejectByType.put(TYPE_VULGAR, textHits.size());
        }
        // 兜底:调用方未送类别汇总时,REVIEW 命中词整体归 VULGAR 桶
        if (reviewByType.isEmpty() && !reviewHits.isEmpty()) {
            reviewByType.put(TYPE_VULGAR, reviewHits.size());
        }

        // REJECT 桶:任一命中即满分拒绝
        for (Map.Entry<String, Integer> e : rejectByType.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            int weight = Math.min(TEXT_REJECT_PER_WORD * e.getValue(), TEXT_BUCKET_CAP);
            String evidence = "命中 " + e.getValue() + " 个拒绝级敏感词(" + e.getKey() + ")";
            buckets.add(e.getKey(), weight, evidence);
            items.add(new RiskItem("违禁词命中", weight, evidence));
        }
        if (!rejectByType.isEmpty()) {
            if (!textHits.isEmpty()) {
                reasons.add("命中违禁词:" + String.join("、", textHits));
            } else {
                reasons.add("命中违禁词:" + rejectByType.size() + " 个类型");
            }
            return true;
        }

        // REVIEW 桶:每词 15 分,桶内封顶
        for (Map.Entry<String, Integer> e : reviewByType.entrySet()) {
            int weight = Math.min(TEXT_REVIEW_PER_WORD * e.getValue(), TEXT_BUCKET_CAP);
            String evidence = "命中 " + e.getValue() + " 个人审级敏感词(" + e.getKey() + ")";
            buckets.add(e.getKey(), weight, evidence);
            items.add(new RiskItem("疑似违规词命中", weight, evidence));
        }
        if (!reviewHits.isEmpty()) {
            reasons.add("命中疑似违规词:" + String.join("、", reviewHits));
        }
        return false;
    }

    // ==================== 皮肤占比(色情) ====================

    /** 皮肤占比规则:avg>0.45 或 max>0.6 记 PORN 70 分;0.30≤avg≤0.45 记 PORN 35 分。 */
    private void scoreSkin(RiskRequest request, List<String> reasons, List<RiskItem> items, Buckets buckets) {
        Double avg = request == null ? null : request.avgSkinRatio();
        Double max = request == null ? null : request.maxSkinRatio();
        if (avg == null && max == null) {
            return;
        }
        String avgText = avg == null ? "-" : String.format("%.2f", avg);
        String maxText = max == null ? "-" : String.format("%.2f", max);
        if ((avg != null && avg > SKIN_HARD_AVG_THRESHOLD) || (max != null && max > SKIN_HARD_MAX_THRESHOLD)) {
            String evidence = String.format("平均肤色占比 %s/最高 %s,疑似色情内容", avgText, maxText);
            buckets.add(TYPE_PORN, SKIN_HARD_WEIGHT, evidence);
            items.add(new RiskItem("肤色占比过高", SKIN_HARD_WEIGHT, evidence));
            reasons.add("画面肤色占比过高,疑似色情");
        } else if (avg != null && avg >= SKIN_SUSPECT_AVG_THRESHOLD) {
            String evidence = String.format("平均肤色占比 %s/最高 %s,超出可疑阈值", avgText, maxText);
            buckets.add(TYPE_PORN, SKIN_SUSPECT_WEIGHT, evidence);
            items.add(new RiskItem("肤色占比偏高", SKIN_SUSPECT_WEIGHT, evidence));
            reasons.add("画面肤色占比偏高");
        }
    }

    // ==================== 黑样本命中 ====================

    /** 黑样本规则:命中样本的各类型各 +80(同类型多命中只计一次),证据带最近汉明距离。 */
    private void scoreBlacklist(RiskRequest request, List<String> reasons, List<RiskItem> items, Buckets buckets) {
        List<BlacklistHit> hits = request == null || request.blacklistHits() == null
                ? List.of() : request.blacklistHits();
        if (hits.isEmpty()) {
            return;
        }
        Map<String, List<BlacklistHit>> byType = new LinkedHashMap<>();
        for (BlacklistHit hit : hits) {
            if (hit == null) {
                continue;
            }
            byType.computeIfAbsent(normalizeType(hit.type()), k -> new ArrayList<>()).add(hit);
        }
        for (Map.Entry<String, List<BlacklistHit>> e : byType.entrySet()) {
            int nearest = e.getValue().stream().mapToInt(BlacklistHit::distance).min().orElse(0);
            String evidence = "感知哈希命中 " + e.getValue().size() + " 个黑样本(" + e.getKey()
                    + ",最近汉明距离 " + nearest + ")";
            buckets.add(e.getKey(), BLACKLIST_HIT_WEIGHT, evidence);
            items.add(new RiskItem("黑样本命中", BLACKLIST_HIT_WEIGHT, evidence));
        }
        reasons.add("画面命中违规黑样本");
    }

    // ==================== 画面机审(不类型化,归 OTHER 桶) ====================

    /** 画面规则打分:黑帧占比、静帧、无音频、低分辨率、时长越界。 */
    private void scoreScreen(JsonNode screen, List<String> reasons, List<RiskItem> items, Buckets buckets) {
        double blackRatio = screen.path("black_ratio").asDouble(0.0);
        if (blackRatio >= BLACK_HARD_THRESHOLD) {
            String evidence = String.format("黑帧占比 %.2f,画面疑似黑屏", blackRatio);
            buckets.add(TYPE_OTHER, BLACK_HARD_WEIGHT, evidence);
            items.add(new RiskItem("黑屏", BLACK_HARD_WEIGHT, evidence));
            reasons.add("画面疑似黑屏");
        } else if (blackRatio >= BLACK_SUSPECT_THRESHOLD) {
            String evidence = String.format("黑帧占比 %.2f,超出可疑阈值", blackRatio);
            buckets.add(TYPE_OTHER, BLACK_SUSPECT_WEIGHT, evidence);
            items.add(new RiskItem("黑帧偏高", BLACK_SUSPECT_WEIGHT, evidence));
            reasons.add("黑帧占比偏高");
        }

        if (screen.path("static_suspect").asBoolean(false)) {
            String evidence = "抽帧画面几乎无变化,疑似纯色/静帧";
            buckets.add(TYPE_OTHER, STATIC_SUSPECT_WEIGHT, evidence);
            items.add(new RiskItem("静帧疑似", STATIC_SUSPECT_WEIGHT, evidence));
            reasons.add("画面疑似静帧或纯色");
        }

        JsonNode meta = screen.path("meta");
        if (meta.isObject()) {
            // 无音频轨:字段缺失时不计分(无法判定)
            if (meta.has("has_audio") && !meta.path("has_audio").asBoolean()) {
                String evidence = "视频不含音频轨";
                buckets.add(TYPE_OTHER, NO_AUDIO_WEIGHT, evidence);
                items.add(new RiskItem("无音频", NO_AUDIO_WEIGHT, evidence));
                reasons.add("视频无音频");
            }

            JsonNode height = meta.path("height");
            JsonNode width = meta.path("width");
            if (height.isNumber() && height.asInt() < RESOLUTION_FLOOR) {
                String resolution = (width.isNumber() ? String.valueOf(width.asInt()) : "?") + "x" + height.asInt();
                String evidence = "分辨率 " + resolution + ",低于 " + RESOLUTION_FLOOR + "p";
                buckets.add(TYPE_OTHER, LOW_RESOLUTION_WEIGHT, evidence);
                items.add(new RiskItem("低分辨率", LOW_RESOLUTION_WEIGHT, evidence));
                reasons.add("视频分辨率低于" + RESOLUTION_FLOOR + "p");
            }

            JsonNode duration = meta.path("duration_sec");
            if (duration.isNumber()) {
                double d = duration.asDouble();
                if (d < MIN_DURATION_SEC || d > MAX_DURATION_SEC) {
                    String evidence = String.format("时长 %.1fs,不在 [%s, %s] 秒区间",
                            d, MIN_DURATION_SEC, MAX_DURATION_SEC);
                    buckets.add(TYPE_OTHER, DURATION_OUT_OF_RANGE_WEIGHT, evidence);
                    items.add(new RiskItem("时长越界", DURATION_OUT_OF_RANGE_WEIGHT, evidence));
                    reasons.add("视频时长越界");
                }
            }
        }
    }

    // ==================== 汇总与分级 ====================

    /** 汇总:总分封顶 100,action 三档定级,level 沿用旧 REJECT 展示语义,violations 按分降序。 */
    private RiskResult finish(int rawScore, List<String> reasons, List<RiskItem> items, Buckets buckets) {
        int score = Math.min(rawScore, SCORE_CAP);
        String action = actionFor(score);
        String level = ACTION_BLOCK.equals(action) ? LEVEL_REJECT : action;
        return new RiskResult(score, level, reasons, items, action, buckets.violations());
    }

    /** 动作映射:≥70 BLOCK,30~69 REVIEW,<30 PASS。 */
    static String actionFor(int score) {
        if (score >= BLOCK_SCORE_THRESHOLD) {
            return ACTION_BLOCK;
        }
        if (score >= REVIEW_SCORE_THRESHOLD) {
            return ACTION_REVIEW;
        }
        return ACTION_PASS;
    }

    /** 分级映射:≥70 REJECT,30~69 REVIEW,<30 PASS(与 actionFor 一一对应)。 */
    static String levelFor(int score) {
        return ACTION_BLOCK.equals(actionFor(score)) ? LEVEL_REJECT : actionFor(score);
    }

    /** 类型归一:未知类型按 OTHER 处理,保证 violations 的类型恒在契约集合内。 */
    static String normalizeType(String type) {
        if (type == null) {
            return TYPE_OTHER;
        }
        return switch (type.trim().toUpperCase()) {
            case TYPE_POLITIC -> TYPE_POLITIC;
            case TYPE_PORN -> TYPE_PORN;
            case TYPE_VULGAR -> TYPE_VULGAR;
            case TYPE_AD -> TYPE_AD;
            default -> TYPE_OTHER;
        };
    }

    /** 类型分桶累计:分值累加、证据追加,汇总时生成按分降序的 violations。 */
    private static final class Buckets {

        private final Map<String, Bucket> byType = new LinkedHashMap<>();

        void add(String type, int score, String evidence) {
            byType.computeIfAbsent(type, k -> new Bucket()).add(score, evidence);
        }

        int total() {
            return byType.values().stream().mapToInt(b -> b.score).sum();
        }

        List<Violation> violations() {
            List<Violation> list = new ArrayList<>();
            for (Map.Entry<String, Bucket> e : byType.entrySet()) {
                if (e.getValue().score > 0) {
                    list.add(new Violation(e.getKey(), e.getValue().score,
                            String.join(";", e.getValue().evidence)));
                }
            }
            list.sort((a, b) -> Integer.compare(b.score(), a.score()));
            return list;
        }

        private static final class Bucket {
            private int score;
            private final List<String> evidence = new ArrayList<>();

            void add(int delta, String item) {
                score += delta;
                evidence.add(item);
            }
        }
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
