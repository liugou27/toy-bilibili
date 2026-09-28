package com.toys.video.risk.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toys.video.risk.dto.RiskRequest;
import com.toys.video.risk.dto.RiskResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 评级引擎单测:分级边界、REJECT 文本直满、组合加权、空输入。 */
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
