package com.toys.video.moderation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 违规黑样本:已确认违规视频的感知哈希,机审按汉明距离匹配命中即类型化加分。 */
@Data
@TableName("violation_samples")
public class ViolationSample {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 感知哈希(64bit,以无符号 long 存储,hex 展示 16 位)。 */
    private Long phash;

    /** POLITIC | PORN | VULGAR | AD | OTHER */
    private String type;

    private String note;

    private LocalDateTime createdAt;
}
