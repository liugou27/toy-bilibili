package com.toys.video.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 内部违规上报:moderation 判定违规后调用,videoId 可空。 */
public record ViolationReport(
        @NotNull Long userId,
        @NotBlank
        @Pattern(regexp = "^(POLITIC|PORN|VULGAR|AD|OTHER)$", message = "违规类型不合法")
        String type,
        @Size(max = 300, message = "违规原因最长 300 字")
        String reason,
        Long videoId
) {
}
