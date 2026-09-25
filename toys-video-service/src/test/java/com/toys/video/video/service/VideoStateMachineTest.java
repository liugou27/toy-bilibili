package com.toys.video.video.service;

import com.toys.video.api.enums.VideoStatus;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流转表以 VideoStateMachine 源码为准:
 * AUTO_SCREENING → UNDER_REVIEW | REJECTED
 * UNDER_REVIEW → APPROVED | REJECTED
 * APPROVED → TRANSCODING
 * TRANSCODING → PUBLISHED | TRANSCODE_FAILED
 * UPLOADED→AUTO_SCREENING 由上传路径直接写入,不在表内,canTransit 返回 false。
 */
class VideoStateMachineTest {

    private static final Map<VideoStatus, Set<VideoStatus>> TABLE = Map.of(
            VideoStatus.AUTO_SCREENING, Set.of(VideoStatus.UNDER_REVIEW, VideoStatus.REJECTED),
            VideoStatus.UNDER_REVIEW, Set.of(VideoStatus.APPROVED, VideoStatus.REJECTED),
            VideoStatus.APPROVED, Set.of(VideoStatus.TRANSCODING),
            VideoStatus.TRANSCODING, Set.of(VideoStatus.PUBLISHED, VideoStatus.TRANSCODE_FAILED)
    );

    @Test
    void canTransit_allTableEntriesAreTrue() {
        for (var entry : TABLE.entrySet()) {
            for (VideoStatus to : entry.getValue()) {
                assertTrue(VideoStateMachine.canTransit(entry.getKey(), to),
                        entry.getKey() + " -> " + to + " 应为合法流转");
            }
        }
    }

    @Test
    void canTransit_fullMatrixMatchesTable() {
        for (VideoStatus from : VideoStatus.values()) {
            for (VideoStatus to : VideoStatus.values()) {
                boolean expected = TABLE.containsKey(from) && TABLE.get(from).contains(to);
                assertEquals(expected, VideoStateMachine.canTransit(from, to),
                        from + " -> " + to + " 与流转表不符");
            }
        }
    }

    @Test
    void requireTransit_illegalTransitThrowsBizExceptionWithCode2105() {
        BizException ex = assertThrows(BizException.class,
                () -> VideoStateMachine.requireTransit(VideoStatus.UPLOADED, VideoStatus.PUBLISHED));
        assertEquals(ErrorCode.VIDEO_STATUS_CONFLICT, ex.getErrorCode());
        assertEquals(2105, ex.getErrorCode().getCode());
    }

    @Test
    void requireTransit_legalTransitDoesNotThrow() {
        assertDoesNotThrow(() -> VideoStateMachine.requireTransit(VideoStatus.AUTO_SCREENING, VideoStatus.UNDER_REVIEW));
        assertDoesNotThrow(() -> VideoStateMachine.requireTransit(VideoStatus.TRANSCODING, VideoStatus.PUBLISHED));
    }

    @Test
    void targets_terminalStatesAreEmpty() {
        assertEquals(Set.of(), VideoStateMachine.targets(VideoStatus.REJECTED));
        assertEquals(Set.of(), VideoStateMachine.targets(VideoStatus.TRANSCODE_FAILED));
    }

    @Test
    void targets_initialStateAndIntermediateStates() {
        assertEquals(Set.of(), VideoStateMachine.targets(VideoStatus.UPLOADED));
        assertEquals(TABLE.get(VideoStatus.AUTO_SCREENING), VideoStateMachine.targets(VideoStatus.AUTO_SCREENING));
        assertEquals(TABLE.get(VideoStatus.TRANSCODING), VideoStateMachine.targets(VideoStatus.TRANSCODING));
    }
}
