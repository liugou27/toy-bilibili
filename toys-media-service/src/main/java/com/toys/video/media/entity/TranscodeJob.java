package com.toys.video.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.toys.video.media.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "transcode_jobs", autoResultMap = true)
public class TranscodeJob {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long videoId;

    /** PENDING | RUNNING | SUCCESS | FAILED */
    private String status;

    private Integer attempts;

    private Integer maxAttempts;

    private String error;

    /** 转码档位信息(脚本输出)。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String payload;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
