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
import com.toys.video.moderation.entity.ViolationSample;
import com.toys.video.moderation.feign.UserPunishClient;
import com.toys.video.moderation.mapper.ModerationReportMapper;
import com.toys.video.moderation.risk.RiskEvaluateClient;
import com.toys.video.common.text.SensitiveWordFilter;
import com.toys.video.moderation.util.PHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 审核领域服务:机审结果落库 + 状态回写 + 违规上报闭环 + 人工审核动作。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationService {

    private static final Set<String> AUTO_FAIL_ACTIONS = Set.of("AUTO_FAIL");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 违规类型 → 用户可见文案(拒绝原因展示用)。 */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "POLITIC", "涉政", "PORN", "色情", "VULGAR", "恶俗", "AD", "广告", "OTHER", "其他");

    private final ModerationReportMapper reportMapper;
    private final AutoScreenService autoScreenService;
    private final VideoInternalClient videoInternalClient;
    private final SensitiveWordService sensitiveWordService;
    private final ViolationSampleService violationSampleService;
    private final RiskEvaluateClient riskEvaluateClient;
    private final UserPunishClient userPunishClient;

    /** 事件触发:先文本机审(标题/简介),再画面机审并推进状态。状态回写失败(2105)视为重复/过期投递,幂等跳过。 */
    public void moderate(Long videoId, String objectKey, Long ownerId, String title, String description) {
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

        // 风控评级:组装文本/画面机审结果 + 文本类别汇总 + 皮肤指标 + 黑样本命中调 risk-service 打分定级,
        // verdict 由处置动作映射;服务不可用时降级为本地旧规则(报告标记 riskDegraded)
        Map<String, Integer> textCategories = sensitiveWordService.countTextCategories(rejectHits, reviewHits);
        List<RiskEvaluateClient.BlacklistHit> blacklistHits =
                screenResult == null ? List.of() : matchBlacklistSamples(screenResult);
        RiskOutcome risk = assessRisk(rejectHits, reviewHits, screenResult, textCategories, blacklistHits);
        String verdict = risk.verdict();

        ModerationReport report = new ModerationReport();
        report.setVideoId(videoId);
        report.setAutoVerdict(verdict);
        report.setAutoReport(buildReportJson(textFail, rejectHits, reviewHits, screenResult, risk));
        report.setRiskScore(risk.score());
        report.setRiskLevel(risk.level());
        boolean autoReject = AUTO_FAIL_ACTIONS.contains(verdict);
        // 最高分违规类型:拒绝文案与违规上报按此定型(降级时为 null)
        String violationType = topViolationType(risk);
        // 硬失败自动拒绝:报告直接终态,不再进人工队列
        report.setDecision(autoReject ? "REJECTED" : "PENDING");
        String rejectReason = null;
        if (autoReject) {
            if (textFail) {
                String words = String.join("、", rejectHits);
                rejectReason = violationType != null
                        ? "机审未通过:涉嫌违规(" + typeLabel(violationType) + "),文本包含违规内容(" + words + ")"
                        : "机审未通过:文本包含违规内容(" + words + ")";
            } else {
                rejectReason = violationType != null
                        ? "机审自动拒绝:涉嫌违规(" + typeLabel(violationType) + ")"
                        : "机审自动拒绝:内容不合规或文件异常";
            }
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
                autoReject ? rejectReason : null);
        applyStatus(videoId, update);
        // 违规闭环:自动拒绝即上报 user-service 记用户违规(计数/禁言/封禁由其裁决);失败仅告警不影响拒绝结论
        if (autoReject) {
            reportUserViolation(ownerId, videoId, violationType, risk);
        }
        log.info("video {} auto-screened: {} -> {} (risk score={} level={} action={} type={} degraded={})",
                videoId, verdict, update.target(), risk.score(), risk.level(), risk.action(),
                violationType, risk.degraded());
    }

    // ==================== 风控评级(risk-service) ====================

    /** 评级结论:score/level/action/items/violations 仅在评级成功时非空,verdict 恒有值。 */
    private record RiskOutcome(Integer score, String level, String action,
                               List<RiskEvaluateClient.RiskItem> items,
                               List<RiskEvaluateClient.Violation> violations,
                               boolean degraded, String verdict) {
    }

    /**
     * 调 risk-service 打分,处置动作映射机审结论:BLOCK→AUTO_FAIL、REVIEW→AUTO_SUSPECT、PASS→AUTO_PASS。
     * 调用失败(兜底抛 SERVICE_UNAVAILABLE 等)降级:本地沿用旧规则——文本 REJECT→AUTO_FAIL,
     * 否则直接采画面机审结论(AUTO_FAIL/AUTO_SUSPECT/AUTO_PASS)。
     */
    private RiskOutcome assessRisk(List<String> rejectHits, List<String> reviewHits, JsonNode screenResult,
                                   Map<String, Integer> textCategories,
                                   List<RiskEvaluateClient.BlacklistHit> blacklistHits) {
        try {
            JsonNode meta = screenResult == null ? null : screenResult.path("meta");
            var resp = riskEvaluateClient.evaluate(new RiskEvaluateClient.RiskRequest(
                    rejectHits, reviewHits, screenResult,
                    doubleOrNull(meta, "avg_skin_ratio"), doubleOrNull(meta, "max_skin_ratio"),
                    textOrNull(meta, "phash"), blacklistHits, textCategories));
            if (resp != null && resp.isSuccess() && resp.data() != null) {
                var result = resp.data();
                return new RiskOutcome(result.score(), result.level(), result.action(), result.riskItems(),
                        result.violations(), false, mapVerdict(result.action(), result.level()));
            }
            log.warn("risk-service evaluate unexpected response: {}", resp);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "风控评级响应异常");
        } catch (Exception e) {
            log.warn("risk-service evaluate failed, degrade to local rules: {}", e.getMessage());
            String verdict = !rejectHits.isEmpty() || screenResult == null
                    ? "AUTO_FAIL"
                    : screenResult.path("verdict").asText("AUTO_FAIL");
            return new RiskOutcome(null, null, null, List.of(), List.of(), true, verdict);
        }
    }

    /** 处置动作映射机审结论:优先 action(BLOCK 沿用 REJECT 语义),未知按可疑处理转人工复核兜底。 */
    private static String mapVerdict(String action, String level) {
        String a = action != null ? action
                : ("REJECT".equals(level) ? "BLOCK" : level == null ? "" : level);
        return switch (a) {
            case "BLOCK" -> "AUTO_FAIL";
            case "REVIEW" -> "AUTO_SUSPECT";
            case "PASS" -> "AUTO_PASS";
            default -> "AUTO_SUSPECT";
        };
    }

    /** 黑样本匹配:画面 phash 与全量样本逐个算汉明距离,≤阈值放入 blacklistHits(无 phash/空库返回空)。 */
    private List<RiskEvaluateClient.BlacklistHit> matchBlacklistSamples(JsonNode screenResult) {
        Long phash = PHashUtil.fromHex(screenResult.path("meta").path("phash").asText(null));
        if (phash == null) {
            return List.of();
        }
        List<RiskEvaluateClient.BlacklistHit> hits = new ArrayList<>();
        for (ViolationSample sample : violationSampleService.current()) {
            int distance = PHashUtil.hamming(phash, sample.getPhash());
            if (distance <= PHashUtil.HAMMING_THRESHOLD) {
                hits.add(new RiskEvaluateClient.BlacklistHit(
                        sample.getType(), PHashUtil.toHex(sample.getPhash()), distance));
            }
        }
        if (!hits.isEmpty()) {
            log.info("blacklist matched for phash {}: {} hit(s)", PHashUtil.toHex(phash), hits.size());
        }
        return hits;
    }

    /** 最高分违规的类型(violations 已按分降序);降级或无违规时为 null。 */
    private static String topViolationType(RiskOutcome risk) {
        if (risk.degraded() || risk.violations() == null || risk.violations().isEmpty()) {
            return null;
        }
        return risk.violations().get(0).type();
    }

    /** 违规类型 → 用户可见文案。 */
    private static String typeLabel(String type) {
        return TYPE_LABELS.getOrDefault(type, "其他");
    }

    // ==================== 违规上报闭环(user-service) ====================

    /**
     * 违规上报:action=BLOCK 的机审拒绝即调 user-service 记用户违规(计数/禁言/封禁由其裁决)。
     * 调用必须 try/catch:失败仅告警降级(不记违规),视频照常拒绝,不阻断审核主流程。
     */
    private void reportUserViolation(Long userId, Long videoId, String violationType, RiskOutcome risk) {
        if (userId == null) {
            log.warn("video {} auto-rejected but owner unknown, skip violation report", videoId);
            return;
        }
        String type = violationType != null ? violationType : "OTHER";
        String reason = buildViolationReason(risk);
        try {
            var resp = userPunishClient.reportViolation(
                    new UserPunishClient.ViolationReport(userId, type, reason, videoId));
            if (resp != null && resp.isSuccess() && resp.data() != null) {
                var data = resp.data();
                log.info("violation reported: user={} video={} type={} count={} muted={} banned={}",
                        userId, videoId, type, data.violationCount(), data.muted(), data.banned());
            } else {
                log.warn("violation report unexpected response: user={} video={} resp={}", userId, videoId, resp);
            }
        } catch (Exception e) {
            log.warn("violation report failed (degrade: skip record, video still rejected): user={} video={} cause={}",
                    userId, videoId, e.getMessage());
        }
    }

    /** 违规原因:汇总全部违规项证据(按分降序,带类型前缀),截断 200 字;降级时给通用文案。 */
    private static String buildViolationReason(RiskOutcome risk) {
        if (risk.degraded() || risk.violations() == null || risk.violations().isEmpty()) {
            return "机审自动拒绝:内容不合规";
        }
        String reason = risk.violations().stream()
                .map(v -> "[" + v.type() + "] " + v.evidence())
                .collect(Collectors.joining(";"));
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    /** 机审报告 JSON:文本硬失败走文本报告,否则保留画面机审结果;叠加风控评级字段(含 action 与违规明细)。 */
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
            node.put("action", risk.action());
            node.set("riskItems", OBJECT_MAPPER.valueToTree(risk.items()));
            node.set("violations", OBJECT_MAPPER.valueToTree(risk.violations()));
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

    /** meta 下的数值字段(皮肤占比),缺失/非数值返回 null。 */
    private static Double doubleOrNull(JsonNode meta, String field) {
        if (meta == null) {
            return null;
        }
        JsonNode n = meta.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }

    /** meta 下的文本字段(phash),缺失/非文本返回 null。 */
    private static String textOrNull(JsonNode meta, String field) {
        if (meta == null) {
            return null;
        }
        JsonNode n = meta.path(field);
        return n.isTextual() ? n.asText() : null;
    }
}
