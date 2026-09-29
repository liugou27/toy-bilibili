package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.mapper.TranscodeJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租约回收:周期扫描租约过期的 RUNNING 作业(持约实例失联)并就地重新派发,
 * 抢占的原子性保证集群内只有一个实例真正接手;与 MQ 重投递互为兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseReaper {

    private final TranscodeJobMapper jobMapper;
    private final TranscodeService transcodeService;
    private final VideoInternalClient videoInternalClient;

    @Scheduled(fixedDelayString = "${toys.transcode.reap-interval-ms:30000}",
            initialDelayString = "${toys.transcode.reap-interval-ms:30000}")
    public void reapExpired() {
        List<TranscodeJob> expired = jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getStatus, "RUNNING")
                .lt(TranscodeJob::getLeaseUntil, LocalDateTime.now()));
        for (TranscodeJob job : expired) {
            if (job.getObjectKey() == null) {
                log.warn("job {} for video {} lease expired without object key, terminal fail",
                        job.getId(), job.getVideoId());
                jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                        .eq(TranscodeJob::getId, job.getId())
                        .set(TranscodeJob::getStatus, "FAILED")
                        .set(TranscodeJob::getError, "租约过期且缺少原片地址")
                        .set(TranscodeJob::getLeaseUntil, null));
                videoInternalClient.updateStatus(job.getVideoId(),
                        new VideoInternalClient.InternalStatusUpdate("TRANSCODE_FAILED", null, null, null,
                                "转码中断,请重试"));
                continue;
            }
            log.warn("job {} for video {} lease expired (owner {}), redispatch",
                    job.getId(), job.getVideoId(), job.getOwnerInstance());
            Thread.ofVirtual().name("lease-redispatch-" + job.getVideoId())
                    .start(() -> {
                        try {
                            transcodeService.process(job.getVideoId(), job.getObjectKey());
                        } catch (Exception e) {
                            log.error("redispatch failed for video {}: {}", job.getVideoId(), e.getMessage());
                        }
                    });
        }
    }
}
