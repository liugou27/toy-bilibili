package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.mapper.TranscodeJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 视频删除后的转码作业数据清理。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeCleanupService {

    private final TranscodeJobMapper jobMapper;

    public void purgeByVideo(Long videoId) {
        int rows = jobMapper.delete(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getVideoId, videoId));
        log.info("purged {} transcode jobs for deleted video {}", rows, videoId);
    }
}
