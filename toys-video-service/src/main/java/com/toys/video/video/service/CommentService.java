package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.api.feign.UserInternalClient;
import com.toys.video.common.api.PageResult;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.dto.CommentItem;
import com.toys.video.video.entity.Comment;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.CommentMapper;
import com.toys.video.video.mapper.VideoMapper;
import com.toys.video.video.text.SensitiveWordHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评论领域服务:comments 的唯一写入方。
 * 发评论必须通过敏感词筛查,命中即拒绝;删除仅限评论作者本人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    /** 评论正文长度上限,与表字段 VARCHAR(500) 一致。 */
    static final int MAX_CONTENT_LENGTH = 500;

    private final CommentMapper commentMapper;
    private final VideoMapper videoMapper;
    private final UserInternalClient userInternalClient;
    private final SensitiveWordHolder sensitiveWordHolder;

    /** 发评论:视频必须存在,正文非空、≤500 字且不命中敏感词。 */
    @com.toys.video.common.idempotent.Idempotent(scene = "comment", key = "#videoId + ':' + #content", windowSeconds = 10, message = "评论已提交,请勿重复发送")
    public CommentItem post(Long videoId, Long userId, String content) {
        requireVideo(videoId);
        Comment comment = new Comment();
        comment.setVideoId(videoId);
        comment.setUserId(userId);
        comment.setContent(validateContent(content));
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);
        UserInternalClient.UserBrief author = fetchUsers(List.of(userId)).getOrDefault(userId,
                new UserInternalClient.UserBrief(userId, "用户" + userId));
        return new CommentItem(comment.getId(), userId, author.username(), author.nickname(),
                comment.getContent(), comment.getCreatedAt());
    }

    /** 评论分页:按发表时间倒序,公开可读;仅已发布视频可读,防泄漏未发布内容。 */
    public PageResult<CommentItem> page(Long videoId, long page, long size) {
        requireVideo(videoId);
        IPage<Comment> p = commentMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getVideoId, videoId)
                        .orderByDesc(Comment::getCreatedAt));
        List<Comment> records = p.getRecords();
        Map<Long, UserInternalClient.UserBrief> users = records.isEmpty() ? Map.of()
                : fetchUsers(records.stream().map(Comment::getUserId).distinct().toList());
        List<CommentItem> items = records.stream()
                .map(c -> {
                    UserInternalClient.UserBrief author = users.getOrDefault(c.getUserId(),
                            new UserInternalClient.UserBrief(c.getUserId(), "用户" + c.getUserId()));
                    return new CommentItem(c.getId(), c.getUserId(), author.username(), author.nickname(),
                            c.getContent(), c.getCreatedAt());
                })
                .toList();
        return new PageResult<>(items, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 删除评论:仅评论作者本人,不是视频 owner。 */
    public void delete(Long videoId, Long commentId, Long requesterId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || !comment.getVideoId().equals(videoId)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "评论不存在");
        }
        if (!comment.getUserId().equals(requesterId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        commentMapper.deleteById(commentId);
    }

    /** 视频删除时级联清理评论。 */
    public void deleteByVideo(Long videoId) {
        commentMapper.delete(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getVideoId, videoId));
    }

    /** 正文校验:非空、≤500 字,命中 REJECT 级敏感词即拒绝;返回 trim 后正文。 */
    String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "评论内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "评论不能超过" + MAX_CONTENT_LENGTH + "字");
        }
        String trimmed = content.trim();
        if (!sensitiveWordHolder.current().screen(trimmed).isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "内容包含违规词汇");
        }
        return trimmed;
    }

    /** 评论前提:视频存在且已发布;未发布视频不可评论/读取评论。 */
    private void requireVideo(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        if (!VideoStatus.PUBLISHED.name().equals(video.getStatus())) {
            throw BizException.of(ErrorCode.VIDEO_NOT_PUBLISHED);
        }
    }

    /** 批量取用户资料;Feign 失败时以「用户{id}」兜底,昵称视为未设置。 */
    private Map<Long, UserInternalClient.UserBrief> fetchUsers(List<Long> userIds) {
        try {
            return userInternalClient.batch(userIds).data().stream()
                    .collect(Collectors.toMap(UserInternalClient.UserBrief::id,
                            Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("fetch user names failed: {}", e.getMessage());
            return userIds.stream().collect(Collectors.toMap(Function.identity(),
                    id -> new UserInternalClient.UserBrief(id, "用户" + id), (a, b) -> a));
        }
    }
}
