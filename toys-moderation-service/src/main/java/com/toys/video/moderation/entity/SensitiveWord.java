package com.toys.video.moderation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 敏感词:词库中心托管,REJECT 硬拒绝,REVIEW 仅记录供人审参考。 */
@Data
@TableName("sensitive_words")
public class SensitiveWord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String word;

    /** REJECT | REVIEW */
    private String level;

    private String category;

    /** ENABLED | DISABLED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
