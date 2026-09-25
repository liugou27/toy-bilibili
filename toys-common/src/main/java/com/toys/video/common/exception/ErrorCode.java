package com.toys.video.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    OK(0, "ok"),

    UNAUTHORIZED(1001, "未登录或登录已过期"),
    FORBIDDEN(1002, "无权访问"),
    NOT_FOUND(1003, "资源不存在"),
    PARAM_INVALID(1004, "参数不合法"),

    USERNAME_TAKEN(2001, "用户名已被占用"),
    BAD_CREDENTIALS(2002, "用户名或密码错误"),

    VIDEO_NOT_FOUND(2101, "视频不存在"),
    VIDEO_NOT_PUBLISHED(2102, "视频暂不可见"),
    VIDEO_TOO_LARGE(2103, "视频超过大小限制"),
    VIDEO_TYPE_FORBIDDEN(2104, "不支持的文件类型"),
    VIDEO_STATUS_CONFLICT(2105, "视频状态不允许该操作"),
    VIDEO_TITLE_INVALID(2106, "标题不合法"),

    MODERATION_NOT_PENDING(3001, "视频不在待审核状态"),

    TRANSCODE_FAILED(4001, "转码失败"),

    INTERNAL_ERROR(5000, "服务内部错误"),
    SERVICE_UNAVAILABLE(5001, "服务暂时不可用,请稍后重试"),
    REQUEST_TIMEOUT(5002, "请求超时,请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
