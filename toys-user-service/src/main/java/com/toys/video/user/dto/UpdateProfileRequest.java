package com.toys.video.user.dto;

import jakarta.validation.constraints.Size;

/** 编辑资料:字段传 null 表示不修改,非 null(可为空串)则覆盖。 */
public record UpdateProfileRequest(
        @Size(max = 32, message = "昵称不能超过32字")
        String nickname,
        @Size(max = 255, message = "头像地址不能超过255字")
        String avatar
) {
}
