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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Set;

/** 播放量:Redis 缓冲 + 定时批量回写 DB,削峰且重启不丢(AOF 兜底);同客户端 24h 去重。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayCountService {

    private static final String KEY_PREFIX = "play:buffer:";
    private static final String DUP_KEY_PREFIX = "play:dup:";
    /** 同一客户端对同一视频 24h 内只计一次。 */
    private static final Duration DUP_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final VideoMapper videoMapper;

    public void recordPlay(Long videoId, String clientKey) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        if (!VideoStatus.PUBLISHED.name().equals(video.getStatus())) {
            throw BizException.of(ErrorCode.VIDEO_NOT_PUBLISHED);
        }
        String dupKey = DUP_KEY_PREFIX + videoId + ":" + md5Hex(clientKey);
        Boolean first = redis.opsForValue().setIfAbsent(dupKey, "1", DUP_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        String key = KEY_PREFIX + videoId;
        redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofHours(2));
    }

    /** 客户端去重键:X-Forwarded-For 首段(缺失回退 remoteAddr)+ "|" + 用户标识,匿名记 anon。 */
    public static String clientKeyOf(String forwardedFor, String remoteAddr, Long userId) {
        String ip = remoteAddr;
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String first = forwardedFor.split(",", -1)[0].trim();
            if (!first.isEmpty()) {
                ip = first;
            }
        }
        return (ip == null ? "" : ip) + "|" + (userId == null ? "anon" : userId);
    }

    /** 小写十六进制 md5:null 视同空串。 */
    static String md5Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 MD5 实现", e);
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void flush() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            try {
                // 先读值回写 DB,成功后再删缓冲 key:DB 失败保留 key 由下轮重试,避免播放量丢失
                String value = redis.opsForValue().get(key);
                if (value == null) {
                    continue;
                }
                long delta = Long.parseLong(value);
                long videoId = Long.parseLong(key.substring(KEY_PREFIX.length()));
                videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                        .eq(Video::getId, videoId)
                        .setSql("play_count = play_count + {0}", delta));
                redis.delete(key);
            } catch (NumberFormatException e) {
                log.warn("skip bad play count key {}", key);
                redis.delete(key);
            } catch (Exception e) {
                log.error("flush play count failed for {}", key, e);
            }
        }
    }
}
