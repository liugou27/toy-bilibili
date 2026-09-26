package com.toys.video.video.service;

import com.toys.video.api.feign.SensitiveWordsInternalClient;
import com.toys.video.api.feign.UserInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.text.SensitiveWordFilter;
import com.toys.video.video.dto.CommentItem;
import com.toys.video.video.entity.Comment;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.CommentMapper;
import com.toys.video.video.mapper.VideoMapper;
import com.toys.video.video.text.SensitiveWordHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private VideoMapper videoMapper;
    @Mock
    private UserInternalClient userInternalClient;

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        commentService = new CommentService(
            commentMapper, videoMapper, userInternalClient, sensitiveWordHolder(Map.of()));
    }

    /** 构造已从 moderation-service 快照加载指定词库的 holder。 */
    private SensitiveWordHolder sensitiveWordHolder(Map<String, String> words) {
        SensitiveWordsInternalClient client = mock(SensitiveWordsInternalClient.class);
        when(client.snapshot(0L)).thenReturn(
                R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(1L, words)));
        SensitiveWordHolder holder = new SensitiveWordHolder(client);
        holder.initialLoad();
        return holder;
    }

    @Test
    void post_rejectsSensitiveWord() {
        commentService = new CommentService(commentMapper, videoMapper, userInternalClient,
                sensitiveWordHolder(Map.of("赌博", SensitiveWordFilter.LEVEL_REJECT)));
        when(videoMapper.selectById(1L)).thenReturn(new Video());
        BizException e = assertThrows(BizException.class,
                () -> commentService.post(1L, 42L, "来看赌博网站"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertEquals("内容包含违规词汇", e.getMessage());
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void post_insertsWithTrimmedContentAndFilledUsername() {
        when(videoMapper.selectById(1L)).thenReturn(new Video());
        when(commentMapper.insert(any(Comment.class))).thenAnswer(inv -> {
            inv.getArgument(0, Comment.class).setId(100L);
            return 1;
        });
        when(userInternalClient.batch(List.of(42L)))
                .thenReturn(R.ok(List.of(new UserInternalClient.UserBrief(42L, "小明"))));
        CommentItem item = commentService.post(1L, 42L, "  前方高能  ");
        assertEquals(100L, item.id());
        assertEquals(42L, item.userId());
        assertEquals("小明", item.username());
        assertEquals("前方高能", item.content());
    }

    @Test
    void validateContent_blankThrows() {
        BizException e = assertThrows(BizException.class, () -> commentService.validateContent("   "));
        assertEquals("评论内容不能为空", e.getMessage());
    }

    @Test
    void validateContent_overLimitThrows() {
        String content = "评".repeat(CommentService.MAX_CONTENT_LENGTH + 1);
        BizException e = assertThrows(BizException.class, () -> commentService.validateContent(content));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void delete_notAuthorForbidden() {
        Comment comment = new Comment();
        comment.setVideoId(1L);
        comment.setUserId(42L);
        when(commentMapper.selectById(100L)).thenReturn(comment);
        BizException e = assertThrows(BizException.class,
                () -> commentService.delete(1L, 100L, 43L));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(commentMapper, never()).deleteById(100L);
    }

    @Test
    void delete_authorDeletesOwnComment() {
        Comment comment = new Comment();
        comment.setVideoId(1L);
        comment.setUserId(42L);
        when(commentMapper.selectById(100L)).thenReturn(comment);
        commentService.delete(1L, 100L, 42L);
        verify(commentMapper).deleteById(100L);
    }

    @Test
    void delete_commentUnderAnotherVideoNotFound() {
        Comment comment = new Comment();
        comment.setVideoId(2L);
        comment.setUserId(42L);
        when(commentMapper.selectById(100L)).thenReturn(comment);
        BizException e = assertThrows(BizException.class,
                () -> commentService.delete(1L, 100L, 42L));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }
}
