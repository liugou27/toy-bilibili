package com.toys.video.video.dto;

import java.time.LocalDateTime;

/** 评论项:username 由 user-service 批量填充,取不到时回退为「用户{id}」。 */
public record CommentItem(
        Long id,
        Long userId,
        String username,
        String content,
        LocalDateTime createdAt
) {
}
