package com.toys.video.api.event;

import java.io.Serializable;

/** 上传完成事件:video-service → moderation-service。 */
public record VideoUploadedEvent(
        Long videoId,
        String objectKey,
        Long ownerId,
        String originalFilename,
        String title,
        String description
) implements Serializable {
}
