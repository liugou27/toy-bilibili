package com.toys.video.user.controller;

import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.dto.ChangePasswordRequest;
import com.toys.video.user.dto.LoginRequest;
import com.toys.video.user.dto.LoginResponse;
import com.toys.video.user.dto.RegisterRequest;
import com.toys.video.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public R<LoginResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest request) {
        return R.ok(authService.register(req, clientIp(request)));
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
    }

    @PostMapping("/logout")
    public R<Void> logout(@org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7));
        }
        return R.ok();
    }

    /** 修改密码:需登录,成功后当前 token 立即失效,前端跳转登录页。 */
    @PostMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                  @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        authService.changePassword(userId, req.oldPassword(), req.newPassword(), token);
        return R.ok();
    }

    /** 优先取网关透传的 X-Forwarded-For 首段,回退到直连地址。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
