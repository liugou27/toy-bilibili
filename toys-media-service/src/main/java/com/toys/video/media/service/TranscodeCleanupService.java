package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toys.video.media.config.MinioConfig;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import com.toys.video.media.mapper.TranscodeSegmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 视频删除后的转码数据清理:段作业、父作业与对象存储中的段源。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeCleanupService {

    private final TranscodeJobMapper jobMapper;
    private final TranscodeSegmentMapper segmentMapper;
    private final MediaStorageService storageService;

    public void purgeByVideo(Long videoId) {
        int segRows = segmentMapper.delete(new LambdaQueryWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getVideoId, videoId));
        int rows = jobMapper.delete(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getVideoId, videoId));
        // 段源(hls 产物前缀由 video-service 级联清理)
        storageService.removePrefix(MinioConfig.BUCKET_VIDEOS, "segments/" + videoId + "/");
        log.info("purged {} jobs / {} segments for deleted video {}", rows, segRows, videoId);
    }
}
