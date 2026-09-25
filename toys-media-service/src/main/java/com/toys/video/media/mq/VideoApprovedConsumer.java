package com.toys.video.media.mq;

import com.toys.video.api.event.VideoApprovedEvent;
import com.toys.video.common.constant.Headers;
import com.toys.video.media.service.TranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 审核通过事件消费:串行执行转码(CPU 密集,占满核会影响在线服务)。 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = com.toys.video.api.event.Topics.VIDEO_APPROVED,
        consumerGroup = "toys-media-consumer",
        consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET,
        consumeThreadNumber = 1,
        consumeThreadMax = 1)
public class VideoApprovedConsumer implements RocketMQListener<MessageExt> {

    private final TranscodeService transcodeService;

    @Override
    public void onMessage(MessageExt message) {
        VideoApprovedEvent event = parse(message);
        String traceId = message.getUserProperty(Headers.REQUEST_ID);
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            log.info("consumed VIDEO_APPROVED videoId={}, reconsumeTimes={}",
                    event.videoId(), message.getReconsumeTimes());
            transcodeService.process(event.videoId(), event.objectKey());
        } finally {
            MDC.remove("traceId");
        }
    }

    private VideoApprovedEvent parse(MessageExt message) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(message.getBody(), VideoApprovedEvent.class);
        } catch (Exception e) {
            log.error("parse event failed, msgId={}", message.getMsgId(), e);
            throw new IllegalArgumentException("bad event payload", e);
        }
    }
}
