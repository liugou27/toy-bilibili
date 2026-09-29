package com.toys.video.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.toys.video.common.mybatis.JsonbTypeHandler;
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

    /** 持有本作业的实例标识(主机名+随机后缀),成功回写的围栏条件。 */
    private String ownerInstance;

    /** 租约到期时间,心跳续期;过期后其他实例可抢回。 */
    private LocalDateTime leaseUntil;

    /** 原片对象键,租约过期抢回重跑时使用。 */
    private String objectKey;

    /** 转码档位信息(脚本输出)。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String payload;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
