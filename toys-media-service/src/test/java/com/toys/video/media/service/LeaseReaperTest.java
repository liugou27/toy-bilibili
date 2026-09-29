package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import com.toys.video.media.mapper.TranscodeSegmentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 租约回收:父作业/段失联重派与段消息丢失兜底。 */
@ExtendWith(MockitoExtension.class)
class LeaseReaperTest {

    @Mock
    private TranscodeJobMapper jobMapper;
    @Mock
    private TranscodeSegmentMapper segmentMapper;
    @Mock
    private TranscodeService transcodeService;
    @Mock
    private SegmentService segmentService;
    @Mock
    private VideoInternalClient videoInternalClient;

    @InjectMocks
    private LeaseReaper reaper;

    @BeforeAll
    static void initEntityMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TranscodeJob.class);
        TableInfoHelper.initTableInfo(assistant, TranscodeSegment.class);
    }

    @Test
    void expiredJobWithObjectKeyIsRedispatched() {
        TranscodeJob job = expiredJob("original/k.mp4", "RUNNING");
        when(jobMapper.selectList(any())).thenReturn(java.util.List.of(job));
        when(segmentMapper.selectList(any())).thenReturn(java.util.List.of());

        reaper.reapExpired();

        await().atMost(2, SECONDS).untilAsserted(() ->
                verify(transcodeService).process(1L, "original/k.mp4"));
        verify(jobMapper, never()).update(isNull(), any());
    }

    @Test
    void expiredJobWithoutObjectKeyFailsTerminally() {
        TranscodeJob job = expiredJob(null, "RUNNING");
        when(jobMapper.selectList(any())).thenReturn(java.util.List.of(job));
        when(segmentMapper.selectList(any())).thenReturn(java.util.List.of());

        reaper.reapExpired();

        ArgumentCaptor<LambdaUpdateWrapper<TranscodeJob>> captor = wrapperCaptor();
        verify(jobMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("FAILED"),
                captor.getValue().getSqlSet());
        ArgumentCaptor<VideoInternalClient.InternalStatusUpdate> statusCaptor =
                ArgumentCaptor.forClass(VideoInternalClient.InternalStatusUpdate.class);
        verify(videoInternalClient).updateStatus(anyLong(), statusCaptor.capture());
        assertEquals("TRANSCODE_FAILED", statusCaptor.getValue().target());
        verify(transcodeService, never()).process(anyLong(), any());
    }

    @Test
    void expiredRunningSegmentIsRedispatched() {
        TranscodeSegment seg = new TranscodeSegment();
        seg.setId(200L);
        seg.setVideoId(1L);
        seg.setStatus("RUNNING");
        seg.setLeaseUntil(LocalDateTime.now().minusSeconds(60));
        seg.setOwnerInstance("dead-host-00000000");
        // 第一次 selectList(段 RUNNING 过期),后续查询返回空
        when(segmentMapper.selectList(any())).thenReturn(java.util.List.of(seg))
                .thenReturn(java.util.List.of());
        when(jobMapper.selectList(any())).thenReturn(java.util.List.of());

        reaper.reapExpired();

        await().atMost(2, SECONDS).untilAsserted(() ->
                verify(segmentService).processSegment(200L));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<TranscodeJob>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    private TranscodeJob expiredJob(String objectKey, String status) {
        TranscodeJob job = new TranscodeJob();
        job.setId(100L);
        job.setVideoId(1L);
        job.setStatus(status);
        job.setAttempts(1);
        job.setMaxAttempts(3);
        job.setLeaseUntil(LocalDateTime.now().minusSeconds(60));
        job.setOwnerInstance("dead-host-00000000");
        job.setObjectKey(objectKey);
        return job;
    }
}
