package com.toys.video.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.moderation.entity.ModerationReport;
import com.toys.video.moderation.mapper.ModerationReportMapper;
import com.toys.video.moderation.risk.RiskEvaluateClient;
import com.toys.video.common.text.SensitiveWordFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 审核领域服务:机审结果落库 + 状态回写 + 人工审核动作。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationService {

    private static final Set<String> AUTO_FAIL_ACTIONS = Set.of("AUTO_FAIL");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ModerationReportMapper reportMapper;
    private final AutoScreenService autoScreenService;
    private final VideoInternalClient videoInternalClient;
    private final SensitiveWordService sensitiveWordService;
    private final RiskEvaluateClient riskEvaluateClient;

    /** 事件触发:先文本机审(标题/简介),再画面机审并推进状态。状态回写失败(2105)视为重复/过期投递,幂等跳过。 */
    public void moderate(Long videoId, String objectKey, String title, String description) {
        ModerationReport existing = reportMapper.selectOne(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId));
        if (existing != null) {
            // 补偿:报告已存在但视频仍卡在 AUTO_SCREENING(上次回写失败)→ 按报告结论补写状态
            var brief = videoInternalClient.batch(List.of(videoId));
            if (brief != null && brief.code() == 0 && brief.data() != null && !brief.data().isEmpty()) {
                if (VideoStatus.AUTO_SCREENING.name().equals(brief.data().get(0).status())) {
                    boolean rejectExisting = "REJECTED".equals(existing.getDecision())
                            || "AUTO_FAIL".equals(existing.getAutoVerdict());
                    applyStatus(videoId, new VideoInternalClient.InternalStatusUpdate(
                            rejectExisting ? VideoStatus.REJECTED.name() : VideoStatus.UNDER_REVIEW.name(),
                            null, null, null, existing.getRejectReason()));
                    log.warn("compensated stuck video {} -> {}", videoId,
                            rejectExisting ? VideoStatus.REJECTED : VideoStatus.UNDER_REVIEW);
                }
            }
            log.info("moderation report exists for video {}, skip (idempotent)", videoId);
            return;
        }

        // 文本机审先行:REJECT 命中即硬失败,直接走自动拒绝,跳过画面机审省资源;
        // 仅 REVIEW 命中不拒绝,记入机审报告供人审参考,流程照常
        SensitiveWordFilter filter = sensitiveWordService.current();
        Map<String, List<String>> hits = filter.screenWithLevel(
                (title == null ? "" : title) + "\n" + (description == null ? "" : description));
        List<String> rejectHits = hits.getOrDefault(SensitiveWordFilter.LEVEL_REJECT, List.of());
        List<String> reviewHits = hits.getOrDefault(SensitiveWordFilter.LEVEL_REVIEW, List.of());
        boolean textFail = !rejectHits.isEmpty();

        JsonNode screenResult = null;
        if (rejectHits.isEmpty()) {
            try {
                screenResult = autoScreenService.screen(objectKey);
            } catch (AutoScreenService.SourceVanishedException ve) {
                // 视频已删除:报告/状态均无意义,静默确认消息避免无限重投
                log.info("video {} deleted before screening, ack and skip", videoId);
                return;
            }
        }

        // 风控评级:组装文本/画面机审结果调 risk-service 打分定级,verdict 由评级映射;
        // 服务不可用时降级为本地旧规则(报告标记 riskDegraded)
        RiskOutcome risk = assessRisk(rejectHits, reviewHits, screenResult);
        String verdict = risk.verdict();

        ModerationReport report = new ModerationReport();
        report.setVideoId(videoId);
        report.setAutoVerdict(verdict);
        report.setAutoReport(buildReportJson(textFail, rejectHits, reviewHits, screenResult, risk));
        report.setRiskScore(risk.score());
        report.setRiskLevel(risk.level());
        boolean autoReject = AUTO_FAIL_ACTIONS.contains(verdict);
        // 硬失败自动拒绝:报告直接终态,不再进人工队列
        report.setDecision(autoReject ? "REJECTED" : "PENDING");
        String rejectReason = null;
        if (autoReject) {
            rejectReason = textFail
                    ? "机审未通过:文本包含违规内容(" + String.join("、", rejectHits) + ")"
                    : "机审自动拒绝:内容不合规或文件异常";
            report.setRejectReason(rejectReason);
            report.setDecidedAt(LocalDateTime.now());
        }
        try {
            reportMapper.insert(report);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("report race for video {}, skip", videoId);
            return;
        }

        JsonNode meta = textFail ? null : screenResult.path("meta");
        VideoInternalClient.InternalStatusUpdate update = new VideoInternalClient.InternalStatusUpdate(
                autoReject ? VideoStatus.REJECTED.name() : VideoStatus.UNDER_REVIEW.name(),
                textFail ? null : longOrNull(meta.path("duration_sec")),
                textFail ? null : intOrNull(meta.path("width")),
                textFail ? null : intOrNull(meta.path("height")),
                textFail ? rejectReason
                        : (autoReject ? "机审未通过:内容不合规或文件异常" : null));
        applyStatus(videoId, update);
        log.info("video {} auto-screened: {} -> {} (risk score={} level={} degraded={})",
                videoId, verdict, update.target(), risk.score(), risk.level(), risk.degraded());
    }

    // ==================== 风控评级(risk-service) ====================

    /** 评级结论:score/level/items 仅在评级成功时非空,verdict 恒有值。 */
    private record RiskOutcome(Integer score, String level,
                               List<RiskEvaluateClient.RiskItem> items, boolean degraded, String verdict) {
    }

    /**
     * 调 risk-service 打分,评级映射机审结论:REJECT→AUTO_FAIL、REVIEW→AUTO_SUSPECT、PASS→AUTO_PASS。
     * 调用失败(兜底抛 SERVICE_UNAVAILABLE 等)降级:本地沿用旧规则——文本 REJECT→AUTO_FAIL,
     * 否则直接采画面机审结论(AUTO_FAIL/AUTO_SUSPECT/AUTO_PASS)。
     */
    private RiskOutcome assessRisk(List<String> rejectHits, List<String> reviewHits, JsonNode screenResult) {
        try {
            var resp = riskEvaluateClient.evaluate(
                    new RiskEvaluateClient.RiskRequest(rejectHits, reviewHits, screenResult));
            if (resp != null && resp.isSuccess() && resp.data() != null) {
                var result = resp.data();
                return new RiskOutcome(result.score(), result.level(), result.riskItems(),
                        false, mapVerdict(result.level()));
            }
            log.warn("risk-service evaluate unexpected response: {}", resp);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "风控评级响应异常");
        } catch (Exception e) {
            log.warn("risk-service evaluate failed, degrade to local rules: {}", e.getMessage());
            String verdict = !rejectHits.isEmpty() || screenResult == null
                    ? "AUTO_FAIL"
                    : screenResult.path("verdict").asText("AUTO_FAIL");
            return new RiskOutcome(null, null, List.of(), true, verdict);
        }
    }

    /** 评级映射机审结论:未知评级按可疑处理,转人工复核兜底。 */
    private static String mapVerdict(String level) {
        return switch (level == null ? "" : level) {
            case "REJECT" -> "AUTO_FAIL";
            case "REVIEW" -> "AUTO_SUSPECT";
            case "PASS" -> "AUTO_PASS";
            default -> "AUTO_SUSPECT";
        };
    }

    /** 机审报告 JSON:文本硬失败走文本报告,否则保留画面机审结果;叠加风控评级字段。 */
    private String buildReportJson(boolean textFail, List<String> rejectHits, List<String> reviewHits,
                                   JsonNode screenResult, RiskOutcome risk) {
        ObjectNode node;
        if (textFail) {
            node = OBJECT_MAPPER.createObjectNode();
            node.put("verdict", "AUTO_FAIL");
            node.put("source", "text");
            node.set("hits", OBJECT_MAPPER.valueToTree(rejectHits));
        } else if (screenResult instanceof ObjectNode screenObj) {
            node = screenObj;
        } else {
            // 画面机审输出非对象(理论不可达):包一层避免丢数据
            node = OBJECT_MAPPER.createObjectNode();
            node.set("screen", screenResult);
        }
        if (!reviewHits.isEmpty()) {
            node.set("reviewHits", OBJECT_MAPPER.valueToTree(reviewHits));
        }
        if (risk.degraded()) {
            node.put("riskDegraded", true);
        } else {
            node.put("riskScore", risk.score());
            node.put("riskLevel", risk.level());
            node.set("riskItems", OBJECT_MAPPER.valueToTree(risk.items()));
        }
        return node.toString();
    }

    public Map<String, Object> reviewDetail(Long videoId) {
        ModerationReport report = reportMapper.selectOne(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId));
        if (report == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "机审报告不存在");
        }
        var presignResp = videoInternalClient.presign(videoId, 900);
        // 视频已被删除等场景:明确报错而非 NPE
        if (presignResp == null || presignResp.code() != 0 || presignResp.data() == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "视频不存在或已删除");
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("report", report);
        result.put("presignedUrl", presignResp.data());
        return result;
    }

    /** 视频删除后清理其审核报告(孤儿数据治理)。 */
    public void purgeByVideo(Long videoId) {
        int rows = reportMapper.delete(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId));
        log.info("purged {} moderation reports for deleted video {}", rows, videoId);
    }

    /** 审核动作前置校验:视频状态已被他人推进时直接拒绝,避免报告已改而状态回写失败的不一致。 */
    private void assertVideoPending(Long videoId) {
        var resp = videoInternalClient.batch(List.of(videoId));
        if (resp == null || resp.code() != 0 || resp.data() == null || resp.data().isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "视频不存在");
        }
        if (!VideoStatus.UNDER_REVIEW.name().equals(resp.data().get(0).status())) {
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING);
        }
    }

    // ==================== 审核认领(并发冲突控制) ====================
    //
    // 语义:认领是软锁(显示谁在处理,10 分钟未决策自动释放);
    // 决策是硬锁——条件更新保证同一单只有第一个决策生效,第二人收到明确冲突提示。

    private static final long CLAIM_TIMEOUT_MINUTES = 10;

    /** 认领审核任务:可重复认领自己;他人已认领且未超时则冲突。 */
    public void claim(Long videoId, Long reviewerId) {
        ModerationReport report = requirePendingReport(videoId);
        if (report.getClaimedBy() != null && !report.getClaimedBy().equals(reviewerId)
                && !claimExpired(report)) {
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING, "该任务已被其他审核员认领");
        }
        int rows = reportMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModerationReport>()
                .eq(ModerationReport::getId, report.getId())
                .eq(ModerationReport::getDecision, "PENDING")
                .and(w -> w.isNull(ModerationReport::getClaimedBy)
                        .or().eq(ModerationReport::getClaimedBy, reviewerId)
                        .or().lt(ModerationReport::getClaimedAt,
                                LocalDateTime.now().minusMinutes(CLAIM_TIMEOUT_MINUTES)))
                .set(ModerationReport::getClaimedBy, reviewerId)
                .set(ModerationReport::getClaimedAt, LocalDateTime.now()));
        if (rows == 0) {
            // 读检查与更新之间被他人抢先认领
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING, "该任务已被其他审核员认领");
        }
    }

    /** 状态回写失败时把已决策的报告回滚为 PENDING,保留人工重试入口。 */
    private void revertReportToPending(Long reportId) {
        try {
            reportMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModerationReport>()
                    .eq(ModerationReport::getId, reportId)
                    .in(ModerationReport::getDecision, "APPROVED", "REJECTED")
                    .set(ModerationReport::getDecision, "PENDING")
                    .set(ModerationReport::getReviewerId, null)
                    .set(ModerationReport::getRejectReason, null)
                    .set(ModerationReport::getDecidedAt, null));
            log.warn("report {} reverted to PENDING after status-writeback failure", reportId);
        } catch (Exception ex) {
            log.error("revert report {} failed", reportId, ex);
        }
    }

    /** 决策前校验认领归属:被他人认领且未超时时,决策人必须是认领人(或认领已过期)。 */
    private void assertClaimableBy(ModerationReport report, Long reviewerId) {
        if (report.getClaimedBy() != null
                && !report.getClaimedBy().equals(reviewerId)
                && !claimExpired(report)) {
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING, "该任务已被其他审核员认领");
        }
    }

    private boolean claimExpired(ModerationReport report) {
        return report.getClaimedAt() == null
                || report.getClaimedAt().isBefore(LocalDateTime.now().minusMinutes(CLAIM_TIMEOUT_MINUTES));
    }

    /** 定时释放超时认领,任务回到可认领池。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void releaseExpiredClaims() {
        int rows = reportMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModerationReport>()
                .eq(ModerationReport::getDecision, "PENDING")
                .isNotNull(ModerationReport::getClaimedBy)
                .lt(ModerationReport::getClaimedAt,
                        LocalDateTime.now().minusMinutes(CLAIM_TIMEOUT_MINUTES))
                .set(ModerationReport::getClaimedBy, null)
                .set(ModerationReport::getClaimedAt, null));
        if (rows > 0) {
            log.info("released {} expired review claims", rows);
        }
    }

    public void approve(Long videoId, Long reviewerId) {
        assertVideoPending(videoId);
        ModerationReport report = requirePendingReport(videoId);
        assertClaimableBy(report, reviewerId);
        transitionDecision(videoId, reviewerId, "APPROVED", null);
        try {
            applyStatus(videoId, new VideoInternalClient.InternalStatusUpdate(
                    VideoStatus.APPROVED.name(), null, null, null, null));
        } catch (Exception e) {
            revertReportToPending(report.getId());
            throw e;
        }
        log.info("video {} approved by {}", videoId, reviewerId);
    }

    public void reject(Long videoId, Long reviewerId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "拒绝原因必填");
        }
        assertVideoPending(videoId);
        ModerationReport report = requirePendingReport(videoId);
        assertClaimableBy(report, reviewerId);
        transitionDecision(videoId, reviewerId, "REJECTED", reason.trim());
        try {
            applyStatus(videoId, new VideoInternalClient.InternalStatusUpdate(
                    VideoStatus.REJECTED.name(), null, null, null, reason.trim()));
        } catch (Exception e) {
            revertReportToPending(report.getId());
            throw e;
        }
        log.info("video {} rejected by {}: {}", videoId, reviewerId, reason.trim());
    }

    private ModerationReport requirePendingReport(Long videoId) {
        ModerationReport report = reportMapper.selectOne(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId));
        if (report == null || !"PENDING".equals(report.getDecision())) {
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING);
        }
        return report;
    }

    private void transitionDecision(Long videoId, Long reviewerId, String decision, String reason) {
        ModerationReport report = reportMapper.selectOne(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId));
        if (report == null || !"PENDING".equals(report.getDecision())) {
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING);
        }
        int rows = reportMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModerationReport>()
                .eq(ModerationReport::getId, report.getId())
                .eq(ModerationReport::getDecision, "PENDING")
                .set(ModerationReport::getDecision, decision)
                .set(ModerationReport::getReviewerId, reviewerId)
                .set(ModerationReport::getRejectReason, reason)
                .set(ModerationReport::getClaimedBy, null)
                .set(ModerationReport::getClaimedAt, null)
                .set(ModerationReport::getDecidedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING, "该任务已被其他审核员处理");
        }
    }

    /** 状态回写:2105(状态冲突)说明已被处理,幂等吞掉;其他错误向上抛(消息重试)。 */
    private void applyStatus(Long videoId, VideoInternalClient.InternalStatusUpdate update) {
        var r = videoInternalClient.updateStatus(videoId, update);
        if (r == null) {
            return;
        }
        if (r.code() == ErrorCode.VIDEO_STATUS_CONFLICT.getCode()) {
            log.warn("status conflict for video {} (already advanced), skip", videoId);
            return;
        }
        if (r.code() != 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "状态回写失败: " + r.message());
        }
    }

    private Long longOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asLong();
    }

    private Integer intOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asInt();
    }
}
