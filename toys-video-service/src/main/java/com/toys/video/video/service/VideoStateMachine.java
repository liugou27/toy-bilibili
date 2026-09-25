package com.toys.video.video.service;

import com.toys.video.api.enums.VideoStatus;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * 视频状态机:集中维护合法状态流转表与流转校验。
 * UPLOADED→AUTO_SCREENING 由上传路径直接写入,不经过本表;
 * 同状态幂等重入由调用方自行放行,不属于流转校验。
 */
public final class VideoStateMachine {

    private static final Map<VideoStatus, Set<VideoStatus>> ALLOWED_TRANSITIONS = Map.of(
            VideoStatus.AUTO_SCREENING, Set.of(VideoStatus.UNDER_REVIEW, VideoStatus.REJECTED),
            VideoStatus.UNDER_REVIEW, Set.of(VideoStatus.APPROVED, VideoStatus.REJECTED),
            VideoStatus.APPROVED, Set.of(VideoStatus.TRANSCODING),
            VideoStatus.TRANSCODING, Set.of(VideoStatus.PUBLISHED, VideoStatus.TRANSCODE_FAILED)
    );

    private VideoStateMachine() {
    }

    /** 是否存在 from→to 的合法流转。 */
    public static boolean canTransit(VideoStatus from, VideoStatus to) {
        Set<VideoStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /** from 可流转到的全部目标状态;from 为初始态或终态时返回空集。 */
    public static Set<VideoStatus> targets(VideoStatus from) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
    }

    /** 校验流转,非法时抛 VIDEO_STATUS_CONFLICT。 */
    public static void requireTransit(VideoStatus from, VideoStatus to) {
        if (!canTransit(from, to)) {
            throw new BizException(ErrorCode.VIDEO_STATUS_CONFLICT,
                    "非法状态流转: %s -> %s".formatted(from, to));
        }
    }
}
