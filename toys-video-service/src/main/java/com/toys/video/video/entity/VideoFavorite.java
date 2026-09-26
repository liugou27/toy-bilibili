package com.toys.video.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 视频收藏:唯一键 (video_id, user_id) 保证一人一收藏。 */
@Data
@TableName("video_favorites")
public class VideoFavorite {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long videoId;

    private Long userId;

    private LocalDateTime createdAt;
}
