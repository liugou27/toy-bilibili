package com.toys.video.moderation.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.moderation.dto.ModerationQueueItem;
import com.toys.video.moderation.entity.ModerationReport;
import com.toys.video.moderation.mapper.ModerationReportMapper;
import com.toys.video.moderation.service.ModerationService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 人工审核后台:队列 / 详情 / 通过 / 拒绝。网关已校验 ADMIN 角色,这里二次校验。 */
@RestController
@RequestMapping("/api/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {

    private final ModerationService moderationService;
    private final ModerationReportMapper reportMapper;
    private final VideoInternalClient videoInternalClient;

    @GetMapping("/queue")
    public R<PageResult<ModerationQueueItem>> queue(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size) {
        requireAdmin();
        IPage<ModerationReport> p = reportMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                new LambdaQueryWrapper<ModerationReport>()
                        .eq(ModerationReport::getDecision, "PENDING")
                        .orderByDesc(ModerationReport::getCreatedAt));
        List<Long> videoIds = p.getRecords().stream().map(ModerationReport::getVideoId).toList();
        Map<Long, VideoInternalClient.VideoBrief> briefs = videoIds.isEmpty() ? Map.of()
                : videoInternalClient.batch(videoIds).data().stream()
                        .collect(Collectors.toMap(VideoInternalClient.VideoBrief::id, Function.identity()));
        List<ModerationQueueItem> items = p.getRecords().stream()
                .map(r -> {
                    VideoInternalClient.VideoBrief b = briefs.get(r.getVideoId());
                    return new ModerationQueueItem(r.getVideoId(),
                            b == null ? "(视频已删除)" : b.title(),
                            r.getAutoVerdict(),
                            b == null ? null : b.ownerId(),
                            b == null ? null : b.originalFilename(),
                            b == null ? null : b.sizeBytes(),
                            r.getCreatedAt());
                })
                .toList();
        return R.ok(new PageResult<>(items, p.getTotal(), p.getCurrent(), p.getSize()));
    }

    @GetMapping("/{videoId}")
    public R<Map<String, Object>> detail(@PathVariable Long videoId) {
        requireAdmin();
        return R.ok(moderationService.reviewDetail(videoId));
    }

    @PostMapping("/{videoId}/approve")
    public R<Void> approve(@PathVariable Long videoId) {
        requireAdmin();
        moderationService.approve(videoId, UserContext.userId());
        return R.ok();
    }

    @PostMapping("/{videoId}/reject")
    public R<Void> reject(@PathVariable Long videoId, @RequestBody RejectRequest req) {
        requireAdmin();
        moderationService.reject(videoId, UserContext.userId(), req.getReason());
        return R.ok();
    }

    @Data
    public static class RejectRequest {
        @NotBlank(message = "拒绝原因必填")
        private String reason;
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(UserContext.userRole())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
}
