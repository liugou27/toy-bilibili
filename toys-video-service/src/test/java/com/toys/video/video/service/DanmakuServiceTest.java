package com.toys.video.video.service;

import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.text.SensitiveWordFilter;
import com.toys.video.video.dto.DanmakuItem;
import com.toys.video.video.entity.Danmaku;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.DanmakuMapper;
import com.toys.video.video.mapper.VideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DanmakuServiceTest {

    @Mock
    private DanmakuMapper danmakuMapper;
    @Mock
    private VideoMapper videoMapper;

    private DanmakuService danmakuService;

    @BeforeEach
    void setUp() {
        danmakuService = new DanmakuService(
            danmakuMapper, videoMapper, new SensitiveWordFilter());
    }

    @Test
    void post_rejectsSensitiveWord() {
        when(videoMapper.selectById(1L)).thenReturn(new Video());
        BizException e = assertThrows(BizException.class,
                () -> danmakuService.post(1L, 42L, 10.5, "主播赌博吧"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("内容包含违规词汇", e.getMessage());
        verify(danmakuMapper, never()).insert(any(Danmaku.class));
    }

    @Test
    void post_insertsWithTrimmedContent() {
        when(videoMapper.selectById(1L)).thenReturn(new Video());
        doAnswer(inv -> {
            inv.getArgument(0, Danmaku.class).setId(100L);
            return 1;
        }).when(danmakuMapper).insert(any(Danmaku.class));
        DanmakuItem item = danmakuService.post(1L, 42L, 10.5, "  前方高能  ");
        assertEquals(100L, item.id());
        assertEquals(10.5, item.timeSec());
        assertEquals("前方高能", item.content());
    }

    @Test
    void post_nullTimeSecThrows() {
        BizException e = assertThrows(BizException.class,
                () -> danmakuService.post(1L, 42L, null, "前方高能"));
        assertEquals("播放位置不合法", e.getMessage());
    }

    @Test
    void post_negativeTimeSecThrows() {
        BizException e = assertThrows(BizException.class,
                () -> danmakuService.post(1L, 42L, -0.1, "前方高能"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void validateContent_overLimitThrows() {
        String content = "弹".repeat(DanmakuService.MAX_CONTENT_LENGTH + 1);
        BizException e = assertThrows(BizException.class, () -> danmakuService.validateContent(content));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }
}
