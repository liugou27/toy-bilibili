package com.toys.video.moderation.mq;

import com.toys.video.api.event.VideoUploadedEvent;
import com.toys.video.common.constant.Headers;
import com.toys.video.moderation.service.ModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 上传事件消费:触发自动机审。
 * 消费失败时抛异常交给 RocketMQ 重试;状态冲突(已被处理)在服务层幂等吞掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = com.toys.video.api.event.Topics.VIDEO_UPLOADED,
        consumerGroup = "toys-moderation-consumer",
        consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET,
        consumeThreadNumber = 1,
        consumeThreadMax = 2)
public class VideoUploadedConsumer implements RocketMQListener<MessageExt> {

    private final ModerationService moderationService;

    @Override
    public void onMessage(MessageExt message) {
        VideoUploadedEvent event = parse(message);
        String traceId = message.getUserProperty(Headers.REQUEST_ID);
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            log.info("consumed VIDEO_UPLOADED videoId={}, reconsumeTimes={}",
                    event.videoId(), message.getReconsumeTimes());
            moderationService.moderate(event.videoId(), event.objectKey(), event.title(), event.description());
        } finally {
            MDC.remove("traceId");
        }
    }

    private VideoUploadedEvent parse(MessageExt message) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(message.getBody(), VideoUploadedEvent.class);
        } catch (Exception e) {
            log.error("parse event failed, msgId={}", message.getMsgId(), e);
            throw new IllegalArgumentException("bad event payload", e);
        }
    }
}
