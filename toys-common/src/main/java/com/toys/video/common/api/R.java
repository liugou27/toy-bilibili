package com.toys.video.common.api;

/**
 * 统一响应体。code=0 表示成功,非 0 见 ErrorCode 分段:
 * 1xxx 网关/认证,2xxx 视频,3xxx 审核,4xxx 转码,5xxx 服务内部。
 */
public record R<T>(int code, String message, T data) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static R<Void> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
