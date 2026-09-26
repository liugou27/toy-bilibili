package com.toys.video.api.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** media-service 内部接口:视频删除后的转码作业清理。 */
@FeignClient(name = "toys-media-service", contextId = "transcodeInternalClient", path = "/internal/transcode",
        configuration = InternalFeignConfig.class)
public interface TranscodeInternalClient {

    @DeleteMapping("/jobs/{videoId}")
    R<Void> purgeJobs(@PathVariable("videoId") Long videoId);
}
