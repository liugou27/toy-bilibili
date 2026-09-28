package com.toys.video.moderation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.toys.video.common.mybatis.JsonbTypeHandler;
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

    /** 机查明细 JSON(checks、抽帧统计、元数据、风控评级)。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String autoReport;

    /** 风控评级分 0-100,risk-service 不可用降级时为空。 */
    private Integer riskScore;

    /** 风控评级 PASS | REVIEW | REJECT,降级时为空。 */
    private String riskLevel;

    /** PENDING | APPROVED | REJECTED */
    private String decision;

    private Long reviewerId;

    private String rejectReason;

    private LocalDateTime decidedAt;

    /** 认领人(软锁:10 分钟未决策自动释放)。 */
    private Long claimedBy;

    private LocalDateTime claimedAt;

    private LocalDateTime createdAt;
}
