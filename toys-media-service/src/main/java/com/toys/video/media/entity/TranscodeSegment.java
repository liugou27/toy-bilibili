package com.toys.video.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 转码段子作业:大视频切段后独立租约抢占,并行转码,全部 SUCCESS 后由父作业组装。 */
@Data
@TableName("transcode_segments")
public class TranscodeSegment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long jobId;

    private Long videoId;

    private Integer segIndex;

    /** 段源对象键(videos 桶)。 */
    private String objectKey;

    private Double durationSec;

    /** 本段需要输出的档位,逗号分隔高度,如 "1080,720,480"。 */
    private String ladder;

    /** PENDING | RUNNING | SUCCESS | FAILED */
    private String status;

    private Integer attempts;

    private Integer maxAttempts;

    private String ownerInstance;

    private LocalDateTime leaseUntil;

    private String error;

    private LocalDateTime createdAt;
}
