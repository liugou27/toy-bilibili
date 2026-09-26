package com.toys.video.video.discovery.recommend;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.toys.video.video.entity.Video;
import com.toys.video.video.entity.VideoHistory;
import com.toys.video.video.mapper.VideoHistoryMapper;
import com.toys.video.video.mapper.VideoMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FusionRecommendGatewayTest {

    @Mock
    private VideoMapper videoMapper;
    @Mock
    private VideoHistoryMapper historyMapper;
    @InjectMocks
    private FusionRecommendGateway gateway;

    @BeforeAll
    static void initEntityMetadata() {
        // 单测环境无 MyBatis 启动流程,手工初始化实体的表信息供 lambda 条件构造解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Video.class);
        TableInfoHelper.initTableInfo(assistant, VideoHistory.class);
    }

    // ==================== 融合纯函数 ====================

    @Test
    void fuse_sameVideoInMultipleChannelsKeepsOneEntryWithWeightedSum() {
        List<FusionRecommendGateway.Scored> hot = List.of(
                new FusionRecommendGateway.Scored(1L, 3), new FusionRecommendGateway.Scored(2L, 1));
        // 单元素路:归一化按满分 1.0 计
        List<FusionRecommendGateway.Scored> coocur = List.of(new FusionRecommendGateway.Scored(1L, 2));

        List<FusionRecommendGateway.Scored> fused = FusionRecommendGateway.fuse(hot, coocur, List.of(), 0.4, 0.4, 0.2);

        assertEquals(2, fused.size());
        assertEquals(1L, fused.get(0).videoId());
        // hot 归一 v1=1.0 + 共现单元素 1.0 → 0.4*1.0 + 0.4*1.0
        assertEquals(0.8, fused.get(0).score(), 1e-9);
        assertEquals(2L, fused.get(1).videoId());
        assertEquals(0.0, fused.get(1).score(), 1e-9);
    }

    @Test
    void fuse_multiChannelHitsSumUpAndTiesBreakByIdAsc() {
        List<FusionRecommendGateway.Scored> hot = List.of(
                new FusionRecommendGateway.Scored(1L, 10),
                new FusionRecommendGateway.Scored(2L, 5),
                new FusionRecommendGateway.Scored(3L, 0));
        List<FusionRecommendGateway.Scored> coocur = List.of(
                new FusionRecommendGateway.Scored(1L, 8), new FusionRecommendGateway.Scored(4L, 0));

        List<FusionRecommendGateway.Scored> fused = FusionRecommendGateway.fuse(hot, coocur, List.of(), 0.4, 0.4, 0.2);

        // v1=0.4*1.0+0.4*1.0=0.8, v2=0.4*0.5=0.2, v3=0 与 v4=0 并列按 id 升序
        assertEquals(List.of(1L, 2L, 3L, 4L), fused.stream().map(FusionRecommendGateway.Scored::videoId).toList());
        assertEquals(0.8, fused.get(0).score(), 1e-9);
        assertEquals(0.2, fused.get(1).score(), 1e-9);
        assertEquals(0.0, fused.get(2).score(), 1e-9);
    }

    @Test
    void fuse_emptyChannelsContributeNothing() {
        List<FusionRecommendGateway.Scored> fused =
                FusionRecommendGateway.fuse(List.of(), List.of(), List.of(), 0.4, 0.4, 0.2);
        assertTrue(fused.isEmpty());
    }

    @Test
    void fuse_singleElementChannelTakesFullWeight() {
        List<FusionRecommendGateway.Scored> fused = FusionRecommendGateway.fuse(
                List.of(), List.of(new FusionRecommendGateway.Scored(7L, 5)), List.of(), 0, 0.3, 0);
        assertEquals(1, fused.size());
        assertEquals(7L, fused.get(0).videoId());
        assertEquals(0.3, fused.get(0).score(), 1e-9);
    }

    // ==================== 召回流程(DB 走 mock) ====================

    @Test
    void recommendForUser_anonymousDegradesToHotPlusGlobalCooccur() {
        Video v1 = video(100L, "PUBLISHED");
        Video v2 = video(200L, "PUBLISHED");
        when(videoMapper.selectHotVideos(FusionRecommendGateway.RECALL_LIMIT)).thenReturn(List.of(v1, v2));
        when(videoMapper.selectGlobalCooccurVideoIds(FusionRecommendGateway.RECALL_LIMIT)).thenReturn(List.of(200L));
        when(videoMapper.selectList(any())).thenReturn(List.of(v2));

        IPage<Video> page = gateway.recommendForUser(null, 1, 12);

        // 热度 0.7:v1 归一 1.0;共现 0.3:v2 单元素满分 → v1(0.7) > v2(0.3)
        assertEquals(List.of(100L, 200L), page.getRecords().stream().map(Video::getId).toList());
        verify(videoMapper, never()).selectTopOwnerIds(any());
        verifyNoInteractions(historyMapper);
    }

    @Test
    void recommendForUser_loggedInFusesThreeChannelsAndFiltersUnpublished() {
        Video v1 = video(100L, "PUBLISHED");
        Video v3 = video(300L, "PUBLISHED");
        Video v2 = video(200L, "PUBLISHED");
        Video rejected = video(400L, "REJECTED");
        Video upVideo = video(150L, "PUBLISHED");
        when(historyMapper.selectOne(any())).thenReturn(history(999L));
        when(videoMapper.selectHotVideos(FusionRecommendGateway.RECALL_LIMIT)).thenReturn(List.of(v1, v3, v2));
        when(videoMapper.selectCooccurVideoIds(999L, FusionRecommendGateway.RECALL_LIMIT))
                .thenReturn(List.of(200L, 400L));
        // 第 1 次共现候选装载(过滤掉非 PUBLISHED),第 2 次 UP 偏好查询
        when(videoMapper.selectList(any())).thenReturn(List.of(v2, rejected), List.of(upVideo));
        when(videoMapper.selectTopOwnerIds(42L)).thenReturn(List.of(7L));

        IPage<Video> page = gateway.recommendForUser(42L, 1, 12);

        // v1=0.4*1.0=0.4,v2=0.4*0.0+0.4*1.0=0.4(共现单元素满分),vUp=0.2*1.0=0.2,v3=0.4*0.5=0.2
        // 并列按 id 升序;非 PUBLISHED 的 400 不出现
        List<Long> ids = page.getRecords().stream().map(Video::getId).toList();
        assertEquals(List.of(100L, 200L, 150L, 300L), ids);
        assertFalse(ids.contains(400L));
    }

    @Test
    void related_seedMissingOrUnpublishedReturnsEmptyPage() {
        when(videoMapper.selectById(9L)).thenReturn(null);
        assertTrue(gateway.related(9L, 10).getRecords().isEmpty());

        Video draft = video(8L, "UNDER_REVIEW");
        when(videoMapper.selectById(8L)).thenReturn(draft);
        assertTrue(gateway.related(8L, 10).getRecords().isEmpty());
        verify(videoMapper, never()).selectCooccurVideoIds(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void related_cooccurPrimaryWithHotBackfillExcludingSeed() {
        Video seed = video(1L, "PUBLISHED");
        Video v2 = video(2L, "PUBLISHED");
        Video v3 = video(3L, "PUBLISHED");
        when(videoMapper.selectById(1L)).thenReturn(seed);
        when(videoMapper.selectCooccurVideoIds(1L, 10)).thenReturn(List.of(2L));
        when(videoMapper.selectList(any())).thenReturn(List.of(v2));
        when(videoMapper.selectHotVideos(11)).thenReturn(List.of(seed, v3));

        IPage<Video> page = gateway.related(1L, 10);

        // 共现 1 条在前,热度补齐排除种子自身
        assertEquals(List.of(2L, 3L), page.getRecords().stream().map(Video::getId).toList());
        assertEquals(2, page.getTotal());
    }

    private Video video(long id, String status) {
        Video v = new Video();
        v.setId(id);
        v.setStatus(status);
        v.setOwnerId(1L);
        return v;
    }

    private VideoHistory history(long videoId) {
        VideoHistory h = new VideoHistory();
        h.setVideoId(videoId);
        return h;
    }
}
