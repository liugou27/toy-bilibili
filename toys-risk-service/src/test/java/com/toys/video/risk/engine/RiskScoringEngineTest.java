package com.toys.video.risk.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toys.video.risk.dto.BlacklistHit;
import com.toys.video.risk.dto.RiskRequest;
import com.toys.video.risk.dto.RiskResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 评级引擎单测:分级边界、REJECT 文本直满、组合加权、空输入、皮肤阈值边界、黑样本、类型分桶、action 三档。 */
class RiskScoringEngineTest {

    private final RiskScoringEngine engine = new RiskScoringEngine();

    // ==================== 空输入:0 分 PASS ====================

    @Test
    void 空输入得0分PASS() {
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), null));
        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo("PASS");
        assertThat(result.reasons()).isEmpty();
        assertThat(result.riskItems()).isEmpty();
    }

    @Test
    void 全Null输入得0分PASS() {
        RiskResult result = engine.evaluate(new RiskRequest(null, null, null));
        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo("PASS");
    }

    // ==================== 文本 REJECT 直满 ====================

    @Test
    void 单个REJECT词直接满分拒绝() {
        RiskResult result = engine.evaluate(new RiskRequest(List.of("违禁词甲"), List.of(), null));
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.level()).isEqualTo("REJECT");
        assertThat(result.reasons()).anyMatch(r -> r.contains("违禁词甲"));
        // 风险项仍按每词 40 计权重
        assertThat(result.riskItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.name()).isEqualTo("违禁词命中");
                    assertThat(item.weight()).isEqualTo(40);
                });
    }

    @Test
    void 多个REJECT词封顶100并忽略画面加权() {
        JsonNode screen = screen(0.9, true, false, 320, 240, 0.5);
        RiskResult result = engine.evaluate(new RiskRequest(List.of("a", "b", "c", "d"), List.of("r1"), screen));
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.level()).isEqualTo("REJECT");
        // 文本项权重封顶 100,且不再叠加画面规则
        assertThat(result.riskItems()).singleElement()
                .satisfies(item -> assertThat(item.weight()).isEqualTo(100));
    }

    // ==================== 分级边界:69/70、29/30 ====================

    @Test
    void 分级边界_70分REVIEW与REJECT分界() {
        assertThat(RiskScoringEngine.levelFor(70)).isEqualTo("REJECT");
        assertThat(RiskScoringEngine.levelFor(69)).isEqualTo("REVIEW");
        assertThat(RiskScoringEngine.levelFor(100)).isEqualTo("REJECT");
    }

    @Test
    void 分级边界_30分PASS与REVIEW分界() {
        assertThat(RiskScoringEngine.levelFor(30)).isEqualTo("REVIEW");
        assertThat(RiskScoringEngine.levelFor(29)).isEqualTo("PASS");
        assertThat(RiskScoringEngine.levelFor(0)).isEqualTo("PASS");
    }

    @Test
    void 组合恰好70分定级REJECT() {
        // 疑似违规词 2 个(15*2=30) + 无音频(10) + 分辨率低于480(10) + 静帧疑似(20) = 70
        JsonNode screen = screen(0.0, true, false, 640, 360, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of("r1", "r2"), screen));
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.level()).isEqualTo("REJECT");
        assertThat(result.riskItems()).hasSize(4);
    }

    // ==================== 画面规则分档 ====================

    @Test
    void 黑屏占比达硬阈值得60分() {
        JsonNode screen = screen(0.85, false, true, 1920, 1080, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), screen));
        assertThat(result.score()).isEqualTo(60);
        assertThat(result.level()).isEqualTo("REVIEW");
        assertThat(result.reasons()).contains("画面疑似黑屏");
    }

    @Test
    void 黑屏硬阈值叠加无音频恰好70分() {
        JsonNode screen = screen(0.8, false, false, 1920, 1080, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), screen));
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.level()).isEqualTo("REJECT");
    }

    @Test
    void 黑帧可疑档得25分未到REVIEW() {
        JsonNode screen = screen(0.5, false, true, 1920, 1080, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), screen));
        assertThat(result.score()).isEqualTo(25);
        assertThat(result.level()).isEqualTo("PASS");
    }

    @Test
    void 时长越界得30分进入REVIEW() {
        JsonNode screen = screen(0.0, false, true, 1920, 1080, 0.5);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), screen));
        assertThat(result.score()).isEqualTo(30);
        assertThat(result.level()).isEqualTo("REVIEW");
    }

    @Test
    void 疑似违规词与画面加权合计() {
        // 1 个疑似违规词(15) + 黑帧可疑(25) = 40 → REVIEW
        JsonNode screen = screen(0.4, false, true, 1280, 720, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of("r1"), screen));
        assertThat(result.score()).isEqualTo(40);
        assertThat(result.level()).isEqualTo("REVIEW");
        assertThat(result.riskItems()).hasSize(2);
    }

    @Test
    void 分辨率高度不足480计10分() {
        JsonNode screen = screen(0.0, false, true, 640, 360, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), screen));
        assertThat(result.score()).isEqualTo(10);
        assertThat(result.reasons()).contains("视频分辨率低于480p");
    }

    @Test
    void 正常画面零风险() {
        JsonNode screen = screen(0.0, false, true, 1920, 1080, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), screen));
        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo("PASS");
    }

    // ==================== 皮肤占比阈值边界 ====================

    @Test
    void 平均皮肤占比超过045记PORN70分BLOCK() {
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, 0.46, 0.5, null, null, null));
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.action()).isEqualTo("BLOCK");
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.type()).isEqualTo("PORN");
            assertThat(v.score()).isEqualTo(70);
        });
    }

    @Test
    void 最高皮肤占比超过06记PORN70分BLOCK() {
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, 0.1, 0.61, null, null, null));
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.action()).isEqualTo("BLOCK");
        assertThat(result.violations()).singleElement()
                .satisfies(v -> assertThat(v.type()).isEqualTo("PORN"));
    }

    @Test
    void 平均皮肤占比045整落在可疑档记35分REVIEW() {
        // 0.45 未严格大于 0.45,落入 0.30~0.45 可疑档
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, 0.45, 0.45, null, null, null));
        assertThat(result.score()).isEqualTo(35);
        assertThat(result.action()).isEqualTo("REVIEW");
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.type()).isEqualTo("PORN");
            assertThat(v.score()).isEqualTo(35);
        });
    }

    @Test
    void 平均皮肤占比恰030记35分REVIEW() {
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, 0.30, 0.30, null, null, null));
        assertThat(result.score()).isEqualTo(35);
        assertThat(result.action()).isEqualTo("REVIEW");
    }

    @Test
    void 皮肤占比低于阈值不计分() {
        // avg=0.29 未达 0.30;max=0.60 未严格大于 0.60
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, 0.29, 0.60, null, null, null));
        assertThat(result.score()).isZero();
        assertThat(result.action()).isEqualTo("PASS");
        assertThat(result.violations()).isEmpty();
    }

    // ==================== 黑样本命中 ====================

    @Test
    void 黑样本命中记该类型80分BLOCK() {
        List<BlacklistHit> hits = List.of(new BlacklistHit("PORN", "0123456789abcdef", 5));
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, null, null, "0123456789abcdea", hits, null));
        assertThat(result.score()).isEqualTo(80);
        assertThat(result.action()).isEqualTo("BLOCK");
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.type()).isEqualTo("PORN");
            assertThat(v.score()).isEqualTo(80);
            assertThat(v.evidence()).contains("汉明距离 5");
        });
    }

    @Test
    void 同类型多个黑样本只计一次80分() {
        List<BlacklistHit> hits = List.of(
                new BlacklistHit("PORN", "0123456789abcdef", 3),
                new BlacklistHit("PORN", "fedcba9876543210", 8));
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, null, null, "0123456789abcdea", hits, null));
        assertThat(result.score()).isEqualTo(80);
        assertThat(result.violations()).singleElement()
                .satisfies(v -> assertThat(v.evidence()).contains("2 个黑样本"));
    }

    @Test
    void 黑样本不同类型各自计80分() {
        List<BlacklistHit> hits = List.of(
                new BlacklistHit("POLITIC", "0123456789abcdef", 2),
                new BlacklistHit("PORN", "fedcba9876543210", 9));
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of(), null, null, null, "0123456789abcdea", hits, null));
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.violations()).hasSize(2)
                .allSatisfy(v -> assertThat(v.score()).isEqualTo(80));
    }

    // ==================== 文本类型分桶 ====================

    @Test
    void 文本类别汇总按类型分桶累计() {
        // PORN 2 个 REVIEW 词(15*2=30)+ VULGAR 1 个 REVIEW 词(15)= 45 → REVIEW
        Map<String, Integer> categories = Map.of("PORN:REVIEW", 2, "VULGAR:REVIEW", 1);
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of("词甲", "词乙", "词丙"), null, null, null, null, null, categories));
        assertThat(result.score()).isEqualTo(45);
        assertThat(result.action()).isEqualTo("REVIEW");
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations().get(0).type()).isEqualTo("PORN");
        assertThat(result.violations().get(0).score()).isEqualTo(30);
    }

    @Test
    void REJECT级类别桶任一命中直接满分BLOCK() {
        Map<String, Integer> categories = Map.of("POLITIC:REJECT", 2);
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of("词甲", "词乙"), List.of(), null, null, null, null, null, categories));
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.action()).isEqualTo("BLOCK");
        assertThat(result.level()).isEqualTo("REJECT");
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.type()).isEqualTo("POLITIC");
            assertThat(v.score()).isEqualTo(80);
        });
    }

    @Test
    void REJECT级单类型桶内封顶100() {
        Map<String, Integer> categories = Map.of("PORN:REJECT", 4);
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of("a", "b", "c", "d"), List.of(), null, null, null, null, null, categories));
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.violations()).singleElement()
                .satisfies(v -> assertThat(v.score()).isEqualTo(100));
    }

    @Test
    void 未知类型归入OTHER桶() {
        Map<String, Integer> categories = Map.of("WEIRD:REJECT", 1);
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of("a"), List.of(), null, null, null, null, null, categories));
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.violations()).singleElement()
                .satisfies(v -> assertThat(v.type()).isEqualTo("OTHER"));
    }

    // ==================== action 三档与 violations 排序 ====================

    @Test
    void action三档与level对应() {
        assertThat(RiskScoringEngine.actionFor(70)).isEqualTo("BLOCK");
        assertThat(RiskScoringEngine.actionFor(69)).isEqualTo("REVIEW");
        assertThat(RiskScoringEngine.actionFor(30)).isEqualTo("REVIEW");
        assertThat(RiskScoringEngine.actionFor(29)).isEqualTo("PASS");
        assertThat(RiskScoringEngine.actionFor(0)).isEqualTo("PASS");
        // BLOCK 对应旧 REJECT 展示语义
        assertThat(RiskScoringEngine.levelFor(70)).isEqualTo("REJECT");
    }

    @Test
    void violations按分降序排列() {
        // 皮肤 PORN 35 + 2 个 REVIEW 词 VULGAR 30 = 65 → REVIEW,PORN 在前
        RiskResult result = engine.evaluate(new RiskRequest(
                List.of(), List.of("词甲", "词乙"), null, 0.35, 0.4, null, null, null));
        assertThat(result.score()).isEqualTo(65);
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations().get(0).type()).isEqualTo("PORN");
        assertThat(result.violations().get(0).score()).isEqualTo(35);
        assertThat(result.violations().get(1).type()).isEqualTo("VULGAR");
        assertThat(result.violations().get(1).score()).isEqualTo(30);
    }

    @Test
    void 画面规则归入OTHER违规桶() {
        JsonNode screen = screen(0.85, false, true, 1920, 1080, 60.0);
        RiskResult result = engine.evaluate(new RiskRequest(List.of(), List.of(), screen));
        assertThat(result.score()).isEqualTo(60);
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.type()).isEqualTo("OTHER");
            assertThat(v.score()).isEqualTo(60);
        });
    }

    // ==================== 测试工具 ====================

    /** 构造画面机审结果 JSON:null 字段省略,模拟真实脚本的输出形态。 */
    private static JsonNode screen(Double blackRatio, Boolean staticSuspect, Boolean hasAudio,
                                   Integer width, Integer height, Double durationSec) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (blackRatio != null) {
            node.put("black_ratio", blackRatio);
        }
        node.put("static_suspect", staticSuspect != null && staticSuspect);
        ObjectNode meta = node.putObject("meta");
        if (hasAudio != null) {
            meta.put("has_audio", hasAudio);
        }
        if (width != null) {
            meta.put("width", width);
        }
        if (height != null) {
            meta.put("height", height);
        }
        if (durationSec != null) {
            meta.put("duration_sec", durationSec);
        }
        return node;
    }
}
