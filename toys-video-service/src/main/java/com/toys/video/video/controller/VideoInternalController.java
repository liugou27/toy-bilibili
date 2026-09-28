package com.toys.video.video.controller;

import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.video.mapper.VideoMapper;
import com.toys.video.video.service.VideoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 内部接口:仅限服务间直连(网关已封禁该路径)。 */
@RestController
@RequestMapping("/internal/videos")
@RequiredArgsConstructor
public class VideoInternalController {

    private final VideoService videoService;
    private final VideoMapper videoMapper;

    @PostMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id,
                                @RequestBody VideoInternalClient.InternalStatusUpdate update,
                                HttpServletRequest request) {
        requireInternal(request);
        videoService.updateStatusInternal(id, update);
        return R.ok();
    }

    @PostMapping("/{id}/presign")
    public R<String> presign(@PathVariable Long id,
                             @RequestParam(defaultValue = "900") int expirySeconds,
                             HttpServletRequest request) {
        requireInternal(request);
        // 时长钳制到 [60,3600],防止调用方传入极端值签出超长/过短有效期
        int clamped = Math.max(60, Math.min(3600, expirySeconds));
        return R.ok(videoService.presignedOriginalUrl(id, clamped));
    }

    @GetMapping("/batch")
    public R<List<VideoInternalClient.VideoBrief>> batch(@RequestParam List<Long> ids,
                                                         HttpServletRequest request) {
        requireInternal(request);
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            return R.ok(List.of());
        }
        // 一次批量取回,替代逐 id selectById 的 N+1
        return R.ok(videoMapper.selectByIds(ids).stream()
                .map(v -> new VideoInternalClient.VideoBrief(v.getId(), v.getTitle(), v.getStatus(),
                        v.getOwnerId(), v.getOriginalFilename(), v.getSizeBytes(), v.getNote()))
                .toList());
    }

    private void requireInternal(HttpServletRequest request) {
        String marker = request.getHeader(Headers.INTERNAL_CALL);
        if (!"1".equals(marker)) {
            throw new com.toys.video.common.exception.BizException(
                    com.toys.video.common.exception.ErrorCode.FORBIDDEN);
        }
    }
}
