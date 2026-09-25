package com.toys.video.video.mq;

import com.toys.video.api.event.Topics;
import com.toys.video.api.event.VideoApprovedEvent;
import com.toys.video.api.event.VideoUploadedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/** 事件出口:事件只做触发,状态机写入统一在 VideoService。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    public void publishUploaded(VideoUploadedEvent event) {
        rocketMQTemplate.syncSend(Topics.VIDEO_UPLOADED,
                MessageBuilder.withPayload(event).setHeader("traceId", currentTraceId()).build());
        log.info("published VIDEO_UPLOADED videoId={}", event.videoId());
    }

    public void publishApproved(VideoApprovedEvent event) {
        rocketMQTemplate.syncSend(Topics.VIDEO_APPROVED,
                MessageBuilder.withPayload(event).setHeader("traceId", currentTraceId()).build());
        log.info("published VIDEO_APPROVED videoId={}", event.videoId());
    }

    private String currentTraceId() {
        return org.slf4j.MDC.get("traceId");
    }
}
