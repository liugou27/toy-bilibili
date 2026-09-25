package com.toys.video.media.feign;

import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import org.springframework.cloud.openfeign.FallbackFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** video-service 调用失败兜底:熔断打开/网络异常时快速失败,交由 MQ 重试。 */
@Slf4j
public class VideoInternalClientFallback implements FallbackFactory<VideoInternalClient> {

    @Override
    public VideoInternalClient create(Throwable cause) {
        log.warn("video-service call fallback: {}", cause == null ? "unknown" : cause.getMessage());
        return new VideoInternalClient() {
            @Override
            public R<Void> updateStatus(Long id, VideoInternalClient.InternalStatusUpdate update) {
                throw unavailable();
            }

            @Override
            public R<String> presign(Long id, int expirySeconds) {
                throw unavailable();
            }

            @Override
            public R<List<VideoInternalClient.VideoBrief>> batch(List<Long> ids) {
                throw unavailable();
            }
        };
    }

    private static BizException unavailable() {
        return new BizException(ErrorCode.SERVICE_UNAVAILABLE, "视频服务不可用");
    }
}
