package com.toys.video.api.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * video-service 内部状态接口:机审/转码服务通过它推进视频状态机,
 * 保证 video-service 是状态唯一写入方。
 */
@FeignClient(name = "toys-video-service", contextId = "videoInternalClient", path = "/internal/videos",
        configuration = InternalFeignConfig.class)
public interface VideoInternalClient {

    @PostMapping("/{id}/status")
    R<Void> updateStatus(@PathVariable("id") Long id, @RequestBody InternalStatusUpdate update);

    /**
     * @param target      目标状态(必须合法,非法流转返回 2105)
     * @param durationSec 机审/转码探测出的时长,可空
     * @param width       视频宽,可空
     * @param height      视频高,可空
     * @param note        附加说明(拒绝原因/转码错误),展示给投稿人,可空
     */
    record InternalStatusUpdate(
            String target,
            Long durationSec,
            Integer width,
            Integer height,
            String note
    ) {
    }
}
