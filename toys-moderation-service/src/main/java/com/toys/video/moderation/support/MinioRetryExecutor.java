package com.toys.video.moderation.support;

import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * MinIO 操作重试执行器:网络类异常按 500ms/1s/2s 退避,最多尝试 3 次,
 * 仍失败抛 BizException(INTERNAL_ERROR)交由上层处理。
 */
@Slf4j
@Component
public class MinioRetryExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {500, 1000, 2000};

    public <T> T execute(Supplier<T> op, String what) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return op.get();
            } catch (RuntimeException e) {
                if (!retryable(e)) {
                    throw e;
                }
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    long backoff = BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)];
                    log.warn("minio {} failed (attempt {}/{}), retry in {}ms: {}",
                            what, attempt, MAX_ATTEMPTS, backoff, e.getMessage());
                    if (!sleep(backoff)) {
                        break;
                    }
                }
            }
        }
        log.error("minio {} failed after {} attempts", what, MAX_ATTEMPTS, last);
        throw new BizException(ErrorCode.INTERNAL_ERROR, what + " 失败");
    }

    /** 把受检异常转为非受检,便于在 Supplier 中使用;保留原始异常供重试判定。 */
    public static RuntimeException unchecked(Exception e) {
        return e instanceof RuntimeException re ? re : new RuntimeException(e);
    }

    /** 网络类异常(MinIO 错误响应/IO 及其包装)才重试,业务异常直接上抛。 */
    private boolean retryable(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof ErrorResponseException || cur instanceof IOException) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }

    /** 退避等待;被中断时恢复中断标记并返回 false,调用方停止重试。 */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
