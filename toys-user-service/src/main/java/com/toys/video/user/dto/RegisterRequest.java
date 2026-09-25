package com.toys.video.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "须为 3-32 位字母、数字或下划线")
        String username,
        @NotBlank
        @Pattern(regexp = "^\\S{6,64}$", message = "密码须为 6-64 位非空白字符")
        String password
) {
}
