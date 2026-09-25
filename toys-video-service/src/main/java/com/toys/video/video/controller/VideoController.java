package com.toys.video.video.controller;

import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.dto.UploadResponse;
import com.toys.video.video.dto.VideoCard;
import com.toys.video.video.dto.VideoDetail;
import com.toys.video.video.service.PlayCountService;
import com.toys.video.video.service.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;
    private final PlayCountService playCountService;

    /** 上传(multipart):file + title + description。 */
    @PostMapping
    public R<UploadResponse> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("title") String title,
                                    @RequestParam(value = "description", required = false) String description) {
        Long userId = requireUser();
        return R.ok(videoService.upload(file, title, description, userId));
    }

    @GetMapping
    public R<PageResult<VideoCard>> list(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "12") long size,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(videoService.publishedPage(page, Math.min(size, 50), keyword));
    }

    @GetMapping("/{id}")
    public R<VideoDetail> detail(@PathVariable Long id) {
        return R.ok(videoService.detail(id, UserContext.userId(), UserContext.userRole()));
    }

    @PostMapping("/{id}/play")
    public R<Void> play(@PathVariable Long id) {
        playCountService.recordPlay(id);
        return R.ok();
    }

    @PostMapping("/{id}/retry")
    public R<Void> retry(@PathVariable Long id) {
        Long userId = requireUser();
        videoService.retryTranscode(id, userId, UserContext.userRole());
        return R.ok();
    }

    private Long requireUser() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
