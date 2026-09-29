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
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import com.toys.video.media.mapper.TranscodeSegmentMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 编排入口:租约抢占语义 + 切源派发 + 组装触发 + 重入恢复。 */
@ExtendWith(MockitoExtension.class)
class TranscodeServiceTest {

    @Mock
    private TranscodeJobMapper jobMapper;
    @Mock
    private TranscodeSegmentMapper segmentMapper;
    @Mock
    private MediaStorageService storageService;
    @Mock
    private PythonScriptRunner scriptRunner;
    @Mock
    private VideoInternalClient videoInternalClient;
    @Mock
    private SegmentService segmentService;
    @Mock
    private TranscodeAssembler assembler;

    private TranscodeService service;

    @BeforeAll
    static void initEntityMetadata() {
        // 单测环境无 MyBatis 启动流程,手工初始化实体的表信息供 lambda 条件构造解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TranscodeJob.class);
        TableInfoHelper.initTableInfo(assistant, TranscodeSegment.class);
    }

    @BeforeEach
    void setUp() {
        service = new TranscodeService(jobMapper, segmentMapper, storageService, scriptRunner,
                videoInternalClient, new TranscodePolicy(), new InstanceId(),
                segmentService, assembler);
        ReflectionTestUtils.setField(service, "scriptsDir", "/tmp/scripts");
        ReflectionTestUtils.setField(service, "leaseSeconds", 90);
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 3600);
        ReflectionTestUtils.setField(service, "segmentSeconds", 60);
    }

    @Test
    void claim_liveLeaseSkipsEverything() {
        TranscodeJob job = pendingJob();
        job.setStatus("RUNNING");
        job.setLeaseUntil(LocalDateTime.now().plusSeconds(60));
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(0);

        service.process(1L, "original/k.mp4");

        verifyNoInteractions(videoInternalClient, scriptRunner, storageService, segmentService, assembler);
    }

    @Test
    void noSegments_splitsAndDispatchesToCluster() throws Exception {
        TranscodeJob job = pendingJob();
        when(jobMapper.selectOne(any())).thenReturn(job);
        // 第一笔=抢占,第二笔=切源后 WAITING 落库
        when(jobMapper.update(isNull(), any())).thenReturn(1, 1);
        when(segmentMapper.selectList(any())).thenReturn(List.of());
        when(videoInternalClient.updateStatus(anyLong(), any())).thenReturn(R.ok());
        when(scriptRunner.run(any(), any(), any(), any())).thenReturn(splitResult());

        service.process(1L, "original/k.mp4");

        // 段源上传(poster + 2 段) + 段行插入 + 集群派发 + 作业转 WAITING
        verify(storageService, times(3)).uploadFile(anyString(), anyString(), any(), anyString());
        verify(segmentMapper, times(2)).insert(any(TranscodeSegment.class));
        verify(segmentService).dispatch(anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TranscodeJob>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(jobMapper, times(2)).update(isNull(), captor.capture());
        String waiting = captor.getAllValues().get(1).getSqlSet();
        assertTrue(waiting.contains("status"), waiting);
        assertTrue(captor.getAllValues().get(1).getParamNameValuePairs().containsValue("WAITING"),
                "第二笔更新应写 WAITING");
        verify(assembler, never()).assemble(any(), anyList());
    }

    @Test
    void allSegmentsSuccess_takesMergingAndAssembles() {
        TranscodeJob job = pendingJob();
        when(jobMapper.selectOne(any())).thenReturn(job);
        // 第一笔=抢占,第二笔=WAITING/RUNNING→MERGING 抢占
        when(jobMapper.update(isNull(), any())).thenReturn(1, 1);
        when(segmentMapper.selectList(any())).thenReturn(List.of(successSegment(0), successSegment(1)));

        service.process(1L, "original/k.mp4");

        verify(assembler).assemble(any(), anyList());
        verify(scriptRunner, never()).run(any(), any(), any(), any());
    }

    @Test
    void incompleteSegments_redispatchAndWaiting() {
        TranscodeJob job = pendingJob();
        when(jobMapper.selectOne(any())).thenReturn(job);
        // 抢占成功;WAITING 落库成功
        when(jobMapper.update(isNull(), any())).thenReturn(1, 1);
        TranscodeSegment done = successSegment(0);
        TranscodeSegment pending = successSegment(1);
        pending.setStatus("PENDING");
        when(segmentMapper.selectList(any())).thenReturn(List.of(done, pending));
        when(videoInternalClient.updateStatus(anyLong(), any())).thenReturn(R.ok());

        service.process(1L, "original/k.mp4");

        verify(segmentService).redispatchIncomplete(anyList());
        verify(assembler, never()).assemble(any(), anyList());
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
        verify(scriptRunner, never()).run(any(), any(), any(), any());
    }

    private TranscodeJob pendingJob() {
        TranscodeJob job = new TranscodeJob();
        job.setId(100L);
        job.setVideoId(1L);
        job.setStatus("PENDING");
        job.setAttempts(0);
        job.setMaxAttempts(3);
        return job;
    }

    private TranscodeSegment successSegment(int index) {
        TranscodeSegment seg = new TranscodeSegment();
        seg.setId(200L + index);
        seg.setJobId(100L);
        seg.setVideoId(1L);
        seg.setSegIndex(index);
        seg.setLadder("720,480");
        seg.setStatus("SUCCESS");
        seg.setObjectKey("segments/1/000" + index + ".mp4");
        return seg;
    }

    private com.fasterxml.jackson.databind.JsonNode splitResult() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("single", false);
        root.putArray("ladder").add(720).add(480);
        root.put("duration_sec", 150.0);
        root.put("poster", "poster.jpg");
        com.fasterxml.jackson.databind.node.ArrayNode segments = root.putArray("segments");
        ObjectNode seg0 = segments.addObject();
        seg0.put("index", 0).put("file", "seg_0000.mp4").put("duration_sec", 60.0);
        ObjectNode seg1 = segments.addObject();
        seg1.put("index", 1).put("file", "seg_0001.mp4").put("duration_sec", 60.0);
        return root;
    }
}
