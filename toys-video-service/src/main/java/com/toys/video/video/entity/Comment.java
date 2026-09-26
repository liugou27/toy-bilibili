package com.toys.video.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 视频评论:作者本人可删,视频删除时级联清理。 */
@Data
@TableName("comments")
public class Comment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long videoId;

    private Long userId;

    private String content;

    private LocalDateTime createdAt;
}
