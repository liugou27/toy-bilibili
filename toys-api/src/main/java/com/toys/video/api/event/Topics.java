package com.toys.video.api.event;

/** RocketMQ topic 常量。事件只做触发,状态变更统一收敛在 video-service。 */
public final class Topics {
    private Topics() {
    }

    /** 上传落库并进入机审后发布,payload: VideoUploadedEvent。 */
    public static final String VIDEO_UPLOADED = "video-uploaded-topic";
    /** 人工审核通过后发布,payload: VideoApprovedEvent。 */
    public static final String VIDEO_APPROVED = "video-approved-topic";
}
