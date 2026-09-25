package com.toys.video.user.dto;

public record LoginResponse(String token, UserInfo user) {
}
