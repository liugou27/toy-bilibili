package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.entity.Video;
import com.toys.video.video.entity.VideoHistory;
import com.toys.video.video.mapper.VideoHistoryMapper;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 播放历史领域服务:断点续播位置的唯一写入方。
 * 唯一键 (user_id, video_id) 兜底并发上报,upsert 语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final VideoHistoryMapper historyMapper;
    private final VideoMapper videoMapper;

    /** 上报播放进度:upsert(先查后写,唯一键兜底并发插入);负数进度按 0 处理。 */
    public void savePosition(Long videoId, Long userId, Double position) {
        if (position == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "进度不能为空");
        }
        if (videoMapper.selectById(videoId) == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        double pos = normalizePosition(position);
        VideoHistory existing = historyMapper.selectOne(new LambdaQueryWrapper<VideoHistory>()
                .eq(VideoHistory::getUserId, userId)
                .eq(VideoHistory::getVideoId, videoId));
        if (existing != null) {
            historyMapper.update(null, updateWrapper(userId, videoId, pos));
            return;
        }
        VideoHistory history = new VideoHistory();
        history.setUserId(userId);
        history.setVideoId(videoId);
        history.setPositionSec(pos);
        try {
            historyMapper.insert(history);
        } catch (DuplicateKeyException e) {
            // 并发上报撞唯一键:转为更新
            historyMapper.update(null, updateWrapper(userId, videoId, pos));
        }
    }

    /** 我的续播位置:无记录返回 null。 */
    public Double positionOf(Long videoId, Long userId) {
        if (userId == null) {
            return null;
        }
        VideoHistory history = historyMapper.selectOne(new LambdaQueryWrapper<VideoHistory>()
                .eq(VideoHistory::getUserId, userId)
                .eq(VideoHistory::getVideoId, videoId));
        return history == null ? null : history.getPositionSec();
    }

    /** 视频删除时级联清理播放历史。 */
    public void deleteByVideo(Long videoId) {
        historyMapper.delete(new LambdaQueryWrapper<VideoHistory>()
                .eq(VideoHistory::getVideoId, videoId));
    }

    /** 进度归一:负数按 0 处理。 */
    static double normalizePosition(double position) {
        return position < 0 ? 0d : position;
    }

    private LambdaUpdateWrapper<VideoHistory> updateWrapper(Long userId, Long videoId, double position) {
        return new LambdaUpdateWrapper<VideoHistory>()
                .eq(VideoHistory::getUserId, userId)
                .eq(VideoHistory::getVideoId, videoId)
                .set(VideoHistory::getPositionSec, position)
                .set(VideoHistory::getUpdatedAt, LocalDateTime.now());
    }
}
