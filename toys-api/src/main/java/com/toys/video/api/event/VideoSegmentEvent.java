package com.toys.video.api.event;

/** 段转码子任务派发事件:集群内任意实例抢占执行。 */
public record VideoSegmentEvent(
        Long videoId,
        Long jobId,
        Long segmentId
) {
}
