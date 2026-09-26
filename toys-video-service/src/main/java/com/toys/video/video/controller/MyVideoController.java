package com.toys.video.video.controller;

import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.video.dto.VideoCard;
import com.toys.video.video.service.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 我的投稿:含全部状态与失败原因,仅登录用户。 */
@RestController
@RequestMapping("/api/my/videos")
@RequiredArgsConstructor
public class MyVideoController {

    private final VideoService videoService;

    @GetMapping
    public R<PageResult<VideoCard>> mine(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(videoService.mine(requireUser(), page, Math.min(size, 50)));
    }

    /** 我的收藏:按收藏时间倒序。 */
    @GetMapping("/favorites")
    public R<PageResult<VideoCard>> favorites(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(videoService.favoritePage(requireUser(), page, Math.min(size, 50)));
    }

    private Long requireUser() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new com.toys.video.common.exception.BizException(
                    com.toys.video.common.exception.ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
