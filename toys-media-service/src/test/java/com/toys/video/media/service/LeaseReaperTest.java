package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.mapper.TranscodeJobMapper;
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

@ExtendWith(MockitoExtension.class)
class LeaseReaperTest {

    @Mock
    private TranscodeJobMapper jobMapper;
    @Mock
    private TranscodeService transcodeService;
    @Mock
    private VideoInternalClient videoInternalClient;

    @InjectMocks
    private LeaseReaper reaper;

    @BeforeAll
    static void initEntityMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TranscodeJob.class);
    }

    @Test
    void expiredJobWithObjectKeyIsRedispatched() {
        TranscodeJob job = expiredJob("original/k.mp4");
        when(jobMapper.selectList(any())).thenReturn(java.util.List.of(job));

        reaper.reapExpired();

        // 重新派发走虚拟线程,异步断言
        await().atMost(2, SECONDS).untilAsserted(() ->
                verify(transcodeService).process(1L, "original/k.mp4"));
        verify(jobMapper, never()).update(isNull(), any());
        verify(videoInternalClient, never()).updateStatus(anyLong(), any());
    }

    @Test
    void expiredJobWithoutObjectKeyFailsTerminally() {
        TranscodeJob job = expiredJob(null);
        when(jobMapper.selectList(any())).thenReturn(java.util.List.of(job));

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

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<TranscodeJob>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    private TranscodeJob expiredJob(String objectKey) {
        TranscodeJob job = new TranscodeJob();
        job.setId(100L);
        job.setVideoId(1L);
        job.setStatus("RUNNING");
        job.setAttempts(1);
        job.setMaxAttempts(3);
        job.setLeaseUntil(LocalDateTime.now().minusSeconds(60));
        job.setOwnerInstance("dead-host-00000000");
        job.setObjectKey(objectKey);
        return job;
    }
}
