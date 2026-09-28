package com.toys.video.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户违规记录:moderation 判定违规后经内部接口写入。 */
@Data
@TableName("user_violations")
public class UserViolation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被处罚用户。 */
    private Long userId;

    /** POLITIC | PORN | VULGAR | AD | OTHER */
    private String type;

    /** 违规原因说明。 */
    private String reason;

    /** 关联视频,可空(非视频类违规)。 */
    private Long videoId;

    private LocalDateTime createdAt;
}
