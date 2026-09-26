package com.toys.video.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank
        @Pattern(regexp = "^\\S{6,64}$", message = "密码须为 6-64 位非空白字符")
        String newPassword
) {
}
