package com.toys.video.common.web;

import com.toys.video.common.api.R;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.concurrent.RejectedExecutionException;

/** 服务内统一异常出口:业务异常映射错误码,未知异常收敛为 5000。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("biz error: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return R.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        return R.fail(ErrorCode.VIDEO_TOO_LARGE.getCode(), ErrorCode.VIDEO_TOO_LARGE.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNotFound(NoResourceFoundException e) {
        return R.fail(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), "请求体格式错误");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), "参数类型错误");
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public R<Void> handleRejectedExecution(RejectedExecutionException e) {
        log.error("executor rejected", e);
        return R.fail(ErrorCode.INTERNAL_ERROR.getCode(), "服务繁忙,请稍后重试");
    }

}
