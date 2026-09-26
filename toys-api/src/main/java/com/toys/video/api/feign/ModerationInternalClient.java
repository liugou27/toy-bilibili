package com.toys.video.api.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** moderation-service 内部接口:视频删除后的审核报告清理。 */
@FeignClient(name = "toys-moderation-service", contextId = "moderationInternalClient", path = "/internal/moderation",
        configuration = InternalFeignConfig.class)
public interface ModerationInternalClient {

    @DeleteMapping("/reports/{videoId}")
    R<Void> purgeReports(@PathVariable("videoId") Long videoId);
}
