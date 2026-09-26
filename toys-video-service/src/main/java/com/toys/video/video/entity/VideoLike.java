package com.toys.video.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 视频点赞:唯一键 (video_id, user_id) 保证一人一赞。 */
@Data
@TableName("video_likes")
public class VideoLike {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long videoId;

    private Long userId;

    private LocalDateTime createdAt;
}
