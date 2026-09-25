package com.toys.video.user.controller;

import com.toys.video.common.api.R;
import com.toys.video.user.dto.LoginRequest;
import com.toys.video.user.dto.LoginResponse;
import com.toys.video.user.dto.RegisterRequest;
import com.toys.video.user.service.AuthService;
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
    public R<LoginResponse> register(@Valid @RequestBody RegisterRequest req) {
        return R.ok(authService.register(req));
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
    }
}
