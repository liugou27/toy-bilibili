package com.toys.video.video.dto;

import java.time.LocalDateTime;

/**
 * 评论项:username 语义不变(登录名),由 user-service 批量填充,取不到时回退为「用户{id}」;
 * nickname 仅供参考展示,展示侧按 nickname 优先、username 兜底取值。
 */
public record CommentItem(
        Long id,
        Long userId,
        String username,
        String nickname,
        String content,
        LocalDateTime createdAt
) {
}
