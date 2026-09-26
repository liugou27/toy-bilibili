package com.toys.video.media.controller;

import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.media.service.TranscodeCleanupService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部接口:视频删除后的转码作业清理(网关已封禁该路径)。 */
@RestController
@RequestMapping("/internal/transcode")
@RequiredArgsConstructor
public class TranscodeInternalController {

    private final TranscodeCleanupService cleanupService;

    @DeleteMapping("/jobs/{videoId}")
    public R<Void> purgeJobs(@PathVariable Long videoId, HttpServletRequest request) {
        if (!"1".equals(request.getHeader(Headers.INTERNAL_CALL))) {
            throw new com.toys.video.common.exception.BizException(
                    com.toys.video.common.exception.ErrorCode.FORBIDDEN);
        }
        cleanupService.purgeByVideo(videoId);
        return R.ok();
    }
}
