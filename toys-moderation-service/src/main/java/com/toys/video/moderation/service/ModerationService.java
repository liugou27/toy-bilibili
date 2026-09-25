package com.toys.video.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.moderation.entity.ModerationReport;
import com.toys.video.moderation.mapper.ModerationReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** 审核领域服务:机审结果落库 + 状态回写 + 人工审核动作。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationService {

    private static final Set<String> AUTO_FAIL_ACTIONS = Set.of("AUTO_FAIL");

    private final ModerationReportMapper reportMapper;
    private final AutoScreenService autoScreenService;
    private final VideoInternalClient videoInternalClient;

    /** 事件触发:机审并推进状态。状态回写失败(2105)视为重复/过期投递,幂等跳过。 */
    public void moderate(Long videoId, String objectKey) {
        if (reportMapper.selectCount(new LambdaQueryWrapper<ModerationReport>()
                .eq(ModerationReport::getVideoId, videoId)) > 0) {
            log.info("moderation report exists for video {}, skip (idempotent)", videoId);
            return;
        }

        JsonNode result = autoScreenService.screen(objectKey);
        String verdict = result.path("verdict").asText("AUTO_FAIL");
        JsonNode meta = result.path("meta");

        ModerationReport report = new ModerationReport();
        report.setVideoId(videoId);
        report.setAutoVerdict(verdict);
        report.setAutoReport(result.toString());
        report.setDecision("PENDING");
        try {
            reportMapper.insert(report);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("report race for video {}, skip", videoId);
            return;
        }

        VideoInternalClient.InternalStatusUpdate update = new VideoInternalClient.InternalStatusUpdate(
                AUTO_FAIL_ACTIONS.contains(verdict) ? VideoStatus.REJECTED.name() : VideoStatus.UNDER_REVIEW.name(),
                longOrNull(meta.path("duration_sec")),
                intOrNull(meta.path("width")),
                intOrNull(meta.path("height")),
                AUTO_FAIL_ACTIONS.contains(verdict) ? "机审未通过:内容不合规或文件异常" : null);
        applyStatus(videoId, update);
        log.info("video {} auto-screened: {} -> {}", videoId, verdict, update.target());
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

    public void approve(Long videoId, Long reviewerId) {
        transitionDecision(videoId, reviewerId, "APPROVED", null);
        applyStatus(videoId, new VideoInternalClient.InternalStatusUpdate(
                VideoStatus.APPROVED.name(), null, null, null, null));
        log.info("video {} approved by {}", videoId, reviewerId);
    }

    public void reject(Long videoId, Long reviewerId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "拒绝原因必填");
        }
        transitionDecision(videoId, reviewerId, "REJECTED", reason.trim());
        applyStatus(videoId, new VideoInternalClient.InternalStatusUpdate(
                VideoStatus.REJECTED.name(), null, null, null, reason.trim()));
        log.info("video {} rejected by {}: {}", videoId, reviewerId, reason.trim());
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
                .set(ModerationReport::getDecidedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ErrorCode.MODERATION_NOT_PENDING);
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
