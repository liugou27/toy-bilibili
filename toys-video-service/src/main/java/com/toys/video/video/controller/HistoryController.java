package com.toys.video.video.controller;

import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.dto.VideoCard;
import com.toys.video.video.dto.VideoDetail;
import com.toys.video.video.service.HistoryService;
import com.toys.video.video.service.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 播放历史与断点续播。
 * 网关 video-service 仅路由 /api/videos/** 与 /api/my/videos/**,
 * 因此进度上报/查询挂在 /api/videos,历史列表挂在 /api/my/videos。
 */
@RestController
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;
    private final VideoService videoService;

    /** 上报播放进度(upsert),断点续播用。 */
    @PostMapping("/api/videos/{id}/position")
    public R<Void> savePosition(@PathVariable Long id, @RequestBody PositionRequest req) {
        historyService.savePosition(id, requireUser(), req.position());
        return R.ok();
    }

    /** 我的续播位置:无记录返回 0。 */
    @GetMapping("/api/videos/{id}/position")
    public R<Double> position(@PathVariable Long id) {
        Double position = historyService.positionOf(id, requireUser());
        return R.ok(position == null ? 0d : position);
    }

    /** 我的播放历史:按最近观看倒序,卡片带断点位置。 */
    @GetMapping("/api/my/videos/history")
    public R<PageResult<VideoCard>> history(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        return R.ok(videoService.historyPage(requireUser(), page, Math.min(size, 50)));
    }

    public record PositionRequest(Double position) {
    }

    private Long requireUser() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
