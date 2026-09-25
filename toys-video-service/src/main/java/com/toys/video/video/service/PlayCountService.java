package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/** 播放量:Redis 缓冲 + 定时批量回写 DB,削峰且重启不丢(AOF 兜底)。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayCountService {

    private static final String KEY_PREFIX = "play:buffer:";

    private final StringRedisTemplate redis;
    private final VideoMapper videoMapper;

    public void recordPlay(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        if (!VideoStatus.PUBLISHED.name().equals(video.getStatus())) {
            throw BizException.of(ErrorCode.VIDEO_NOT_PUBLISHED);
        }
        String key = KEY_PREFIX + videoId;
        redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofHours(2));
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void flush() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            try {
                String value = redis.opsForValue().getAndDelete(key);
                if (value == null) {
                    continue;
                }
                long delta = Long.parseLong(value);
                long videoId = Long.parseLong(key.substring(KEY_PREFIX.length()));
                videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                        .eq(Video::getId, videoId)
                        .setSql("play_count = play_count + {0}", delta));
            } catch (NumberFormatException e) {
                log.warn("skip bad play count key {}", key);
            } catch (Exception e) {
                log.error("flush play count failed for {}", key, e);
            }
        }
    }
}
