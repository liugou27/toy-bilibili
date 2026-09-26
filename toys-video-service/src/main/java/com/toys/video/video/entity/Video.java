package com.toys.video.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("videos")
public class Video {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long ownerId;

    private String title;

    private String description;

    /** @see com.toys.video.api.enums.VideoStatus */
    private String status;

    private String objectKey;

    private String originalFilename;

    private Long sizeBytes;

    private Double durationSec;

    private Integer width;

    private Integer height;

    private String format;

    private Long playCount;

    private Long likeCount;

    /** 拒绝原因 / 转码错误,展示给投稿人。 */
    private String note;

    /** 文件内容摘要:断点续传恢复与秒传去重。 */
    private String md5;

    /** S3 multipart uploadId(未完成的分片上传会话)。 */
    private String uploadId;

    /** 分片大小(字节),续传时保持一致。 */
    private Long partSize;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
