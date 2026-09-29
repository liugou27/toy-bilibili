package com.toys.video.media.mq;

import com.toys.video.api.event.VideoSegmentEvent;
import com.toys.video.common.constant.Headers;
import com.toys.video.media.service.SegmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 段转码任务消费:同一 consumer group 的全部实例共同分担段任务,
 * 实现"大视频切段后跨实例并行转码"。串行消费与 CPU 密集特性匹配,
 * 段级租约抢占保证同一时段内一个段只被一个实例处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = com.toys.video.api.event.Topics.VIDEO_TRANSCODE_SEGMENT,
        consumerGroup = "toys-media-segment-consumer",
        consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET,
        consumeThreadNumber = 1,
        consumeThreadMax = 1)
public class SegmentConsumer implements RocketMQListener<MessageExt> {

    private final SegmentService segmentService;

    @Override
    public void onMessage(MessageExt message) {
        VideoSegmentEvent event = parse(message);
        String traceId = message.getUserProperty(Headers.REQUEST_ID);
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            log.info("consumed VIDEO_TRANSCODE_SEGMENT segmentId={}, reconsumeTimes={}",
                    event.segmentId(), message.getReconsumeTimes());
            segmentService.processSegment(event.segmentId());
        } finally {
            MDC.remove("traceId");
        }
    }

    private VideoSegmentEvent parse(MessageExt message) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(message.getBody(), VideoSegmentEvent.class);
        } catch (Exception e) {
            log.error("parse segment event failed, msgId={}", message.getMsgId(), e);
            throw new IllegalArgumentException("bad segment event payload", e);
        }
    }
}
