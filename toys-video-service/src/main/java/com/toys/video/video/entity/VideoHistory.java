package com.toys.video.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 播放历史/断点续播:唯一键 (user_id, video_id),position_sec 为续播秒数。 */
@Data
@TableName("play_histories")
public class VideoHistory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long videoId;

    /** 续播位置(秒)。 */
    private Double positionSec;

    private LocalDateTime updatedAt;
}
