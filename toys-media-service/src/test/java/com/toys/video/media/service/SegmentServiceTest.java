package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.media.config.InstanceId;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import com.toys.video.media.mapper.TranscodeSegmentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 段执行:租约抢占语义 + 成功围栏 + 完成检查推进父作业。 */
@ExtendWith(MockitoExtension.class)
class SegmentServiceTest {

    @Mock
    private TranscodeSegmentMapper segmentMapper;
    @Mock
    private TranscodeJobMapper jobMapper;
    @Mock
    private MediaStorageService storageService;
    @Mock
    private PythonScriptRunner scriptRunner;
    @Mock
    private VideoInternalClient videoInternalClient;
    @Mock
    private TranscodeAssembler assembler;
    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private SegmentService service;

    @BeforeAll
    static void initEntityMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TranscodeSegment.class);
        TableInfoHelper.initTableInfo(assistant, TranscodeJob.class);
    }

    @BeforeEach
    void setUp() {
        service = new SegmentService(segmentMapper, jobMapper, storageService, scriptRunner,
                videoInternalClient, assembler, new InstanceId(), rocketMQTemplate);
        ReflectionTestUtils.setField(service, "scriptsDir", "/tmp/scripts");
        ReflectionTestUtils.setField(service, "leaseSeconds", 90);
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 3600);
    }

    @Test
    void liveLeaseSkips() {
        TranscodeSegment seg = runningSeg(0);
        seg.setLeaseUntil(LocalDateTime.now().plusSeconds(60));
        when(segmentMapper.selectById(200L)).thenReturn(seg);
        when(segmentMapper.update(isNull(), any())).thenReturn(0);

        service.processSegment(200L);

        verify(scriptRunner, never()).run(any(), any(), any(), any(), any());
        verify(assembler, never()).assemble(any(), any());
    }

    @Test
    void lastSegmentSuccessTakesMergingAndAssembles() throws Exception {
        TranscodeSegment seg = runningSeg(1);
        seg.setAttempts(0);
        when(segmentMapper.selectById(200L)).thenReturn(seg);
        // 第一笔=抢占,第二笔=段 SUCCESS(围栏)
        when(segmentMapper.update(isNull(), any())).thenReturn(1, 1);
        when(scriptRunner.run(any(), any(), any(), any(), any()))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        // 无在途段,无失败段 → 抢 MERGING 成功
        when(segmentMapper.selectCount(any())).thenReturn(0L, 0L);
        TranscodeJob job = new TranscodeJob();
        job.setId(100L);
        job.setVideoId(1L);
        job.setStatus("WAITING");
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(segmentMapper.selectList(any())).thenReturn(List.of(runningSeg(0), seg));

        service.processSegment(200L);

        await().atMost(2, SECONDS).untilAsserted(() ->
                verify(assembler).assemble(any(), anyList()));
        // 成功围栏:第二笔段更新限定本实例持有
        verify(segmentMapper, times(2)).update(isNull(), any());
    }

    @Test
    void failureWithoutTerminalThrowsForMqRetry() {
        TranscodeSegment seg = runningSeg(0);
        seg.setAttempts(0); // 第 1 次尝试,未到终态
        when(segmentMapper.selectById(200L)).thenReturn(seg);
        when(segmentMapper.update(isNull(), any())).thenReturn(1, 1);
        when(scriptRunner.run(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("ffmpeg died"));
        // 仍有在途段(其他段 RUNNING)→ 不推进父作业
        when(segmentMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.processSegment(200L));

        // FAILED 落库(两笔:围栏成功写入 + 失败写入)
        verify(segmentMapper, times(2)).update(isNull(), any());
        verify(assembler, never()).assemble(any(), any());
    }

    @Test
    void exhaustedAttemptsFailsJobTerminally() {
        TranscodeSegment seg = runningSeg(0);
        seg.setAttempts(3);
        seg.setMaxAttempts(3);
        when(segmentMapper.selectById(200L)).thenReturn(seg);
        // 段终态失败后无在途、存在失败段 → 父终态
        when(segmentMapper.selectCount(any())).thenReturn(0L, 1L);
        TranscodeJob job = new TranscodeJob();
        job.setId(100L);
        job.setVideoId(1L);
        job.setStatus("WAITING");
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);

        service.processSegment(200L);

        verify(scriptRunner, never()).run(any(), any(), any(), any(), any());
        ArgumentCaptor<VideoInternalClient.InternalStatusUpdate> statusCaptor =
                ArgumentCaptor.forClass(VideoInternalClient.InternalStatusUpdate.class);
        verify(videoInternalClient).updateStatus(anyLong(), statusCaptor.capture());
        assertEquals("TRANSCODE_FAILED", statusCaptor.getValue().target());
    }

    private TranscodeSegment runningSeg(int index) {
        TranscodeSegment seg = new TranscodeSegment();
        seg.setId(200L);
        seg.setJobId(100L);
        seg.setVideoId(1L);
        seg.setSegIndex(index);
        seg.setLadder("720,480");
        seg.setStatus("RUNNING");
        seg.setAttempts(1);
        seg.setMaxAttempts(3);
        seg.setLeaseUntil(LocalDateTime.now().minusSeconds(60));
        seg.setObjectKey("segments/1/0000.mp4");
        return seg;
    }
}
