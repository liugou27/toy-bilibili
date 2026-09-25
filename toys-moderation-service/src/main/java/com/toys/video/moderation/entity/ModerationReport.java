package com.toys.video.moderation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.toys.video.moderation.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "moderation_reports", autoResultMap = true)
public class ModerationReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long videoId;

    /** AUTO_PASS | AUTO_SUSPECT | AUTO_FAIL */
    private String autoVerdict;

    /** 机查明细 JSON(checks、抽帧统计、元数据)。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String autoReport;

    /** PENDING | APPROVED | REJECTED */
    private String decision;

    private Long reviewerId;

    private String rejectReason;

    private LocalDateTime decidedAt;

    private LocalDateTime createdAt;
}
