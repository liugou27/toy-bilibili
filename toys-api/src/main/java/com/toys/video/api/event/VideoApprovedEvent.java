package com.toys.video.api.event;

import java.io.Serializable;

/** 审核通过事件:video-service → media-service。 */
public record VideoApprovedEvent(
        Long videoId,
        String objectKey
) implements Serializable {
}
