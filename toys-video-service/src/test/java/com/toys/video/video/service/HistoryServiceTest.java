package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.toys.video.common.exception.BizException;
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
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock
    private VideoHistoryMapper historyMapper;
    @Mock
    private VideoMapper videoMapper;
    @InjectMocks
    private HistoryService historyService;

    @BeforeAll
    static void initEntityMetadata() {
        // 单测环境无 MyBatis 启动流程,手工初始化实体的表信息供 lambda 条件构造解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, VideoHistory.class);
    }

    @Test
    void normalizePosition_negativeClampedToZero() {
        assertEquals(0d, HistoryService.normalizePosition(-3.2));
        assertEquals(0d, HistoryService.normalizePosition(0d));
        assertEquals(7.5d, HistoryService.normalizePosition(7.5));
    }

    @Test
    void savePosition_nullPositionRejected() {
        assertThrows(BizException.class, () -> historyService.savePosition(1L, 42L, null));
        verifyNoInteractions(videoMapper, historyMapper);
    }

    @Test
    void savePosition_videoMissingThrows() {
        when(videoMapper.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> historyService.savePosition(1L, 42L, 10d));
        verifyNoInteractions(historyMapper);
    }

    @Test
    void savePosition_noRecordInserts() {
        when(videoMapper.selectById(1L)).thenReturn(new com.toys.video.video.entity.Video());
        when(historyMapper.selectOne(any())).thenReturn(null);
        historyService.savePosition(1L, 42L, 30.5);
        verify(historyMapper).insert(any(VideoHistory.class));
        verify(historyMapper, never()).update(isNull(), any());
    }

    @Test
    void savePosition_existingUpdates() {
        when(videoMapper.selectById(1L)).thenReturn(new com.toys.video.video.entity.Video());
        when(historyMapper.selectOne(any())).thenReturn(new VideoHistory());
        historyService.savePosition(1L, 42L, 60d);
        verify(historyMapper).update(isNull(), any());
        verify(historyMapper, never()).insert(any(VideoHistory.class));
    }

    @Test
    void savePosition_duplicateKeyFallsBackToUpdate() {
        when(videoMapper.selectById(1L)).thenReturn(new com.toys.video.video.entity.Video());
        when(historyMapper.selectOne(any())).thenReturn(null);
        when(historyMapper.insert(any(VideoHistory.class))).thenThrow(new DuplicateKeyException("dup"));
        historyService.savePosition(1L, 42L, 45d);
        verify(historyMapper).update(isNull(), any());
    }
}
