package com.toys.video.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.moderation.entity.ModerationReport;
import com.toys.video.moderation.mapper.ModerationReportMapper;
import com.toys.video.moderation.text.SensitiveWordFilter;
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
    private final SensitiveWordFilter sensitiveWordFilter;

    /** 事件触发:先文本机审(标题/简介),再画面机审并推进状态。状态回写失败(2105)视为重复/过期投递,幂等跳过。 */
    public void moderate(Long videoId, String objectKey, String title, String description) {
        if (reportMapper.selectCount(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId)) > 0) {
            log.info("moderation report exists for video {}, skip (idempotent)", videoId);
            return;
        }

        // 文本机审先行:标题/简介命中敏感词即硬失败,直接走自动拒绝,跳过画面机审省资源
        List<String> textHits = sensitiveWordFilter.screen(
                (title == null ? "" : title) + "\n" + (description == null ? "" : description));
        boolean textFail = !textHits.isEmpty();

        JsonNode meta = null;
        String verdict;
        String reportJson;
        if (textFail) {
            verdict = "AUTO_FAIL";
            reportJson = textReportJson(textHits);
        } else {
            JsonNode result = autoScreenService.screen(objectKey);
            verdict = result.path("verdict").asText("AUTO_FAIL");
            meta = result.path("meta");
            reportJson = result.toString();
        }

        ModerationReport report = new ModerationReport();
        report.setVideoId(videoId);
        report.setAutoVerdict(verdict);
        report.setAutoReport(reportJson);
        boolean autoReject = textFail || AUTO_FAIL_ACTIONS.contains(verdict);
        // 硬失败自动拒绝:报告直接终态,不再进人工队列
        report.setDecision(autoReject ? "REJECTED" : "PENDING");
        String rejectReason = null;
        if (autoReject) {
            rejectReason = textFail
                    ? "机审未通过:文本包含违规内容(" + String.join("、", textHits) + ")"
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

        VideoInternalClient.InternalStatusUpdate update = new VideoInternalClient.InternalStatusUpdate(
                autoReject ? VideoStatus.REJECTED.name() : VideoStatus.UNDER_REVIEW.name(),
                textFail ? null : longOrNull(meta.path("duration_sec")),
                textFail ? null : intOrNull(meta.path("width")),
                textFail ? null : intOrNull(meta.path("height")),
                textFail ? rejectReason
                        : (autoReject ? "机审未通过:内容不合规或文件异常" : null));
        applyStatus(videoId, update);
        log.info("video {} auto-screened: {} -> {}", videoId, verdict, update.target());
    }

    /** 文本机审报告 JSON:命中来源与命中词,字段对齐画面机审报告的 verdict。 */
    private String textReportJson(List<String> hits) {
        try {
            return OBJECT_MAPPER.writeValueAsString(
                    Map.of("verdict", "AUTO_FAIL", "source", "text", "hits", hits));
        } catch (JsonProcessingException e) {
            log.warn("text report serialize failed", e);
            return "{\"verdict\":\"AUTO_FAIL\",\"source\":\"text\"}";
        }
    }

    public Map<String, Object> reviewDetail(Long videoId) {
        ModerationReport report = reportMapper.selectOne(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId));
        if (report == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "机审报告不存在");
        }
        String presigned = videoInternalClient.presign(videoId, 900).data();
        return Map.of(
                "report", report,
                "presignedUrl", presigned
        );
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
        reportMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModerationReport>()
                .eq(ModerationReport::getId, report.getId())
                .eq(ModerationReport::getDecision, "PENDING")
                .and(w -> w.isNull(ModerationReport::getClaimedBy)
                        .or().eq(ModerationReport::getClaimedBy, reviewerId)
                        .or().lt(ModerationReport::getClaimedAt,
                                LocalDateTime.now().minusMinutes(CLAIM_TIMEOUT_MINUTES)))
                .set(ModerationReport::getClaimedBy, reviewerId)
                .set(ModerationReport::getClaimedAt, LocalDateTime.now()));
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
        applyStatus(videoId, new VideoInternalClient.InternalStatusUpdate(
                VideoStatus.APPROVED.name(), null, null, null, null));
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
        applyStatus(videoId, new VideoInternalClient.InternalStatusUpdate(
                VideoStatus.REJECTED.name(), null, null, null, reason.trim()));
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
