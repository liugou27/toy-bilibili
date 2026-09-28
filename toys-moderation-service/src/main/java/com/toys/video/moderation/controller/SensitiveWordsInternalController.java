package com.toys.video.moderation.controller;

import com.toys.video.api.feign.SensitiveWordsInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.moderation.service.SensitiveWordService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 内部接口:服务间拉取敏感词快照(网关已封禁该路径)。 */
@RestController
@RequestMapping("/internal/sensitive-words")
@RequiredArgsConstructor
public class SensitiveWordsInternalController {

    private final SensitiveWordService sensitiveWordService;

    /** 词库快照:版本一致返回 words=null(客户端无更新)。 */
    @GetMapping("/snapshot")
    public R<SensitiveWordsInternalClient.SensitiveSnapshot> snapshot(
            @RequestParam("version") long version, HttpServletRequest request) {
        requireInternal(request);
        var view = sensitiveWordService.currentView();
        if (view.version() == version) {
            return R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(view.version(), null));
        }
        return R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(view.version(), view.words()));
    }

    private void requireInternal(HttpServletRequest request) {
        String marker = request.getHeader(Headers.INTERNAL_CALL);
        if (!"1".equals(marker)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }
}
