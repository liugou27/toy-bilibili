package com.toys.video.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 视频弹幕:按播放位置展示,不可删除,视频删除时级联清理。 */
@Data
@TableName("danmaku")
public class Danmaku {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long videoId;

    private Long userId;

    private String content;

    /** 弹幕出现位置(秒)。 */
    private Double timeSec;

    private LocalDateTime createdAt;
}
