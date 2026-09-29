package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.util.PythonScriptRunner;
import com.toys.video.media.config.InstanceId;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.mapper.TranscodeJobMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeServiceTest {

    @Mock
    private TranscodeJobMapper jobMapper;
    @Mock
    private MediaStorageService storageService;
    @Mock
    private PythonScriptRunner scriptRunner;
    @Mock
    private VideoInternalClient videoInternalClient;

    private TranscodeService service;

    @BeforeAll
    static void initEntityMetadata() {
        // 单测环境无 MyBatis 启动流程,手工初始化实体的表信息供 lambda 条件构造解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TranscodeJob.class);
    }

    @BeforeEach
    void setUp() {
        service = new TranscodeService(jobMapper, storageService, scriptRunner,
                videoInternalClient, new TranscodePolicy(), new InstanceId());
        ReflectionTestUtils.setField(service, "scriptsDir", "/tmp/scripts");
        ReflectionTestUtils.setField(service, "leaseSeconds", 90);
        // 心跳首跳设在 1 小时后,避免单测期间触发的定时任务干扰断言
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 3600);
    }

    @Test
    void claim_liveLeaseSkipsEverything() {
        TranscodeJob job = runningJob(LocalDateTime.now().plusSeconds(60));
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(0);

        service.process(1L, "original/k.mp4");

        verifyNoInteractions(videoInternalClient, scriptRunner, storageService);
    }

    @Test
    void claim_expiredLeaseRedispatchedAndPublished() throws Exception {
        TranscodeJob job = runningJob(LocalDateTime.now().minusSeconds(120));
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1, 1);
        when(videoInternalClient.updateStatus(anyLong(), any())).thenReturn(R.ok());
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("duration_sec", 42);
        when(scriptRunner.run(any(), any(), any())).thenReturn(payload);

        service.process(1L, "original/k.mp4");

        ArgumentCaptor<LambdaUpdateWrapper<TranscodeJob>> captor = wrapperCaptor();
        verify(jobMapper, times(2)).update(isNull(), captor.capture());
        String claimWhere = captor.getAllValues().get(0).getSqlSegment();
        String claimSet = captor.getAllValues().get(0).getSqlSet();
        // 抢占条件覆盖失联作业(租约过期/为空),抢占写入持有者与租约
        assertTrue(claimWhere.contains("lease_until IS NULL"), claimWhere);
        assertTrue(claimWhere.contains("lease_until <"), claimWhere);
        assertTrue(claimSet.contains("owner_instance"), claimSet);
        assertTrue(claimSet.contains("lease_until"), claimSet);
        // 成功围栏:第二笔更新限定本实例持有
        String fenceWhere = captor.getAllValues().get(1).getSqlSegment();
        assertTrue(fenceWhere.contains("owner_instance"), fenceWhere);

        ArgumentCaptor<VideoInternalClient.InternalStatusUpdate> statusCaptor =
                ArgumentCaptor.forClass(VideoInternalClient.InternalStatusUpdate.class);
        verify(videoInternalClient, times(2)).updateStatus(anyLong(), statusCaptor.capture());
        assertEquals("TRANSCODING", statusCaptor.getAllValues().get(0).target());
        assertEquals("PUBLISHED", statusCaptor.getAllValues().get(1).target());
        assertEquals(42L, statusCaptor.getAllValues().get(1).durationSec());
    }

    @Test
    void successFence_supersededWriterDropsResult() throws Exception {
        TranscodeJob job = runningJob(LocalDateTime.now().minusSeconds(120));
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1, 0);
        when(videoInternalClient.updateStatus(anyLong(), any())).thenReturn(R.ok());
        when(scriptRunner.run(any(), any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode());

        service.process(1L, "original/k.mp4");

        ArgumentCaptor<VideoInternalClient.InternalStatusUpdate> statusCaptor =
                ArgumentCaptor.forClass(VideoInternalClient.InternalStatusUpdate.class);
        verify(videoInternalClient, times(1)).updateStatus(anyLong(), statusCaptor.capture());
        assertFalse("PUBLISHED".equals(statusCaptor.getValue().target()));
    }

    @Test
    void transcodingConflict_alreadyTranscodingMeansReclaimAndProceeds() throws Exception {
        TranscodeJob job = runningJob(LocalDateTime.now().minusSeconds(120));
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1, 1);
        // 第一次置 TRANSCODING 冲突,但视频确实在 TRANSCODING(抢回续跑),应放行
        when(videoInternalClient.updateStatus(anyLong(), any()))
                .thenReturn(R.fail(2105, "conflict"))
                .thenReturn(R.ok());
        when(videoInternalClient.batch(any())).thenReturn(R.ok(List.of(
                new VideoInternalClient.VideoBrief(1L, "t", "TRANSCODING", 1L, "f.mp4", 1L, null))));
        when(scriptRunner.run(any(), any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode());

        service.process(1L, "original/k.mp4");

        ArgumentCaptor<VideoInternalClient.InternalStatusUpdate> statusCaptor =
                ArgumentCaptor.forClass(VideoInternalClient.InternalStatusUpdate.class);
        verify(videoInternalClient, times(2)).updateStatus(anyLong(), statusCaptor.capture());
        assertEquals("PUBLISHED", statusCaptor.getAllValues().get(1).target());
    }

    @Test
    void skipBranch_repairsStuckPublish() throws Exception {
        TranscodeJob job = new TranscodeJob();
        job.setVideoId(1L);
        job.setStatus("SUCCESS");
        job.setPayload("{\"duration_sec\":42}");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(videoInternalClient.batch(any())).thenReturn(R.ok(List.of(
                new VideoInternalClient.VideoBrief(1L, "t", "TRANSCODING", 1L, "f.mp4", 1L, null))));

        service.process(1L, "original/k.mp4");

        ArgumentCaptor<VideoInternalClient.InternalStatusUpdate> statusCaptor =
                ArgumentCaptor.forClass(VideoInternalClient.InternalStatusUpdate.class);
        verify(videoInternalClient, times(1)).updateStatus(anyLong(), statusCaptor.capture());
        assertEquals("PUBLISHED", statusCaptor.getValue().target());
        assertEquals(42L, statusCaptor.getValue().durationSec());
        verify(scriptRunner, never()).run(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<TranscodeJob>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    private TranscodeJob runningJob(LocalDateTime leaseUntil) {
        TranscodeJob job = new TranscodeJob();
        job.setId(100L);
        job.setVideoId(1L);
        job.setStatus("RUNNING");
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setLeaseUntil(leaseUntil);
        job.setObjectKey("original/k.mp4");
        return job;
    }
}
