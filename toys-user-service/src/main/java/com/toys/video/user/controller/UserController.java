package com.toys.video.user.controller;

import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.dto.UpdateProfileRequest;
import com.toys.video.user.dto.UserInfo;
import com.toys.video.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    @GetMapping("/me")
    public R<UserInfo> me() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return R.ok(authService.me(userId));
    }

    /** 编辑资料:仅登录本人;字段不传则保持原值。 */
    @PatchMapping("/me")
    public R<UserInfo> updateMe(@Valid @RequestBody UpdateProfileRequest req) {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return R.ok(authService.updateProfile(userId, req.nickname(), req.avatar()));
    }
}
