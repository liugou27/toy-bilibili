package com.toys.video.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 关注关系:user_id 关注 target_id,唯一约束兜底幂等。 */
@Data
@TableName("follows")
public class Follow {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关注发起人。 */
    private Long userId;

    /** 被关注人。 */
    private Long targetId;

    private LocalDateTime createdAt;
}
