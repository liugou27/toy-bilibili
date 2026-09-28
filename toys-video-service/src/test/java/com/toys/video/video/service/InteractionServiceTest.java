package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.toys.video.common.exception.BizException;
import com.toys.video.video.entity.Video;
import com.toys.video.video.entity.VideoFavorite;
import com.toys.video.video.entity.VideoLike;
import com.toys.video.video.mapper.VideoFavoriteMapper;
import com.toys.video.video.mapper.VideoLikeMapper;
import com.toys.video.video.mapper.VideoMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InteractionServiceTest {

    @Mock
    private VideoLikeMapper likeMapper;
    @Mock
    private VideoFavoriteMapper favoriteMapper;
    @Mock
    private VideoMapper videoMapper;
    @InjectMocks
    private InteractionService interactionService;

    @BeforeAll
    static void initEntityMetadata() {
        // 单测环境无 MyBatis 启动流程,手工初始化实体的表信息供 lambda 条件构造解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Video.class);
        TableInfoHelper.initTableInfo(assistant, VideoLike.class);
        TableInfoHelper.initTableInfo(assistant, VideoFavorite.class);
    }

    @Test
    void like_firstTimeInsertsAndIncrementsCount() {
        when(videoMapper.selectById(1L)).thenReturn(publishedVideo());
        interactionService.like(1L, 42L);
        verify(likeMapper).insert(any(VideoLike.class));
        verify(videoMapper).update(isNull(), any());
    }

    @Test
    void like_duplicateKeyIsSilentWithoutCountChange() {
        when(videoMapper.selectById(1L)).thenReturn(publishedVideo());
        when(likeMapper.insert(any(VideoLike.class))).thenThrow(new DuplicateKeyException("dup"));
        interactionService.like(1L, 42L);
        verify(videoMapper, never()).update(isNull(), any());
    }

    @Test
    void like_unpublishedVideoRejected() {
        Video video = new Video();
        video.setStatus("TRANSCODING");
        when(videoMapper.selectById(1L)).thenReturn(video);
        org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> interactionService.like(1L, 42L));
        verify(likeMapper, never()).insert(any(VideoLike.class));
    }

    @Test
    void like_videoMissingThrows() {
        when(videoMapper.selectById(1L)).thenReturn(null);
        org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> interactionService.like(1L, 42L));
        verify(likeMapper, never()).insert(any(VideoLike.class));
    }

    @Test
    void unlike_deletedRowDecrementsCount() {
        when(likeMapper.delete(any())).thenReturn(1);
        interactionService.unlike(1L, 42L);
        verify(videoMapper).update(isNull(), any());
    }

    @Test
    void unlike_nothingDeletedKeepsCount() {
        when(likeMapper.delete(any())).thenReturn(0);
        interactionService.unlike(1L, 42L);
        verify(videoMapper, never()).update(isNull(), any());
    }

    @Test
    void likedByMe_anonymousReturnsFalse() {
        assertFalse(interactionService.likedByMe(1L, null));
    }

    /** 已发布视频实体(互动前置条件)。 */
    private static Video publishedVideo() {
        Video video = new Video();
        video.setStatus("PUBLISHED");
        return video;
    }
}
