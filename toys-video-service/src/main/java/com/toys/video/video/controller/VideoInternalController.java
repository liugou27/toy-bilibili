package com.toys.video.video.controller;

import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.video.service.VideoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 内部接口:仅限服务间直连(网关已封禁该路径)。 */
@RestController
@RequestMapping("/internal/videos")
@RequiredArgsConstructor
public class VideoInternalController {

    private final VideoService videoService;

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
        return R.ok(videoService.presignedOriginalUrl(id, expirySeconds));
    }

    private void requireInternal(HttpServletRequest request) {
        String marker = request.getHeader(Headers.INTERNAL_CALL);
        if (!"1".equals(marker)) {
            throw new com.toys.video.common.exception.BizException(
                    com.toys.video.common.exception.ErrorCode.FORBIDDEN);
        }
    }
}
