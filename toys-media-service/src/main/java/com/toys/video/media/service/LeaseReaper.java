package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.entity.TranscodeSegment;
import com.toys.video.media.mapper.TranscodeJobMapper;
import com.toys.video.media.mapper.TranscodeSegmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租约回收:周期扫描失联作业/段子任务并重新派发,与 MQ 重投递互为兜底。
 * 覆盖三类恢复:
 * ① 父作业 RUNNING(切源中)/MERGING(组装中)租约过期 → 重派编排入口;
 * ② 段 RUNNING 租约过期(实例失联)、PENDING 滞留(段消息丢失) → 重派段执行;
 * ③ 父作业 WAITING 但段已全部落定(组装失败回退/触发实例崩溃) → 重派编排入口收敛。
 * 缩容/宕机的影响被压缩到"正在执行的那一个段",已完成的段不会重跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseReaper {

    private static final long PENDING_STALE_SECONDS = 120;

    private final TranscodeJobMapper jobMapper;
    private final TranscodeSegmentMapper segmentMapper;
    private final TranscodeService transcodeService;
    private final SegmentService segmentService;
    private final VideoInternalClient videoInternalClient;

    @Scheduled(fixedDelayString = "${toys.transcode.reap-interval-ms:30000}",
            initialDelayString = "${toys.transcode.reap-interval-ms:30000}")
    public void reapExpired() {
        reapJobs();
        reapSegments();
        reapSettledWaiting();
    }

    private void reapJobs() {
        List<TranscodeJob> expired = jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .in(TranscodeJob::getStatus, "RUNNING", "MERGING")
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
            log.warn("job {} for video {} lease expired (owner {}, {}), redispatch",
                    job.getId(), job.getVideoId(), job.getOwnerInstance(), job.getStatus());
            dispatchAsync("job-reap-" + job.getVideoId(),
                    () -> transcodeService.process(job.getVideoId(), job.getObjectKey()));
        }
    }

    private void reapSegments() {
        LocalDateTime now = LocalDateTime.now();
        List<TranscodeSegment> expired = segmentMapper.selectList(new LambdaQueryWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getStatus, "RUNNING")
                .lt(TranscodeSegment::getLeaseUntil, now));
        for (TranscodeSegment seg : expired) {
            log.warn("segment {} (video {}, owner {}) lease expired, redispatch",
                    seg.getId(), seg.getVideoId(), seg.getOwnerInstance());
            dispatchAsync("segment-reap-" + seg.getId(), () -> segmentService.processSegment(seg.getId()));
        }
        List<TranscodeSegment> stale = segmentMapper.selectList(new LambdaQueryWrapper<TranscodeSegment>()
                .eq(TranscodeSegment::getStatus, "PENDING")
                .lt(TranscodeSegment::getCreatedAt, now.minusSeconds(PENDING_STALE_SECONDS)));
        for (TranscodeSegment seg : stale) {
            log.warn("segment {} (video {}) PENDING too long (message lost?), redispatch",
                    seg.getId(), seg.getVideoId());
            dispatchAsync("segment-stale-" + seg.getId(), () -> segmentService.processSegment(seg.getId()));
        }
    }

    /** WAITING 且段全部落定:全 SUCCESS→组装分支;有 FAILED→终态分支(编排入口统一收敛)。 */
    private void reapSettledWaiting() {
        List<TranscodeJob> waiting = jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getStatus, "WAITING"));
        for (TranscodeJob job : waiting) {
            if (job.getObjectKey() == null) {
                continue;
            }
            Long inFlight = segmentMapper.selectCount(new LambdaQueryWrapper<TranscodeSegment>()
                    .eq(TranscodeSegment::getJobId, job.getId())
                    .in(TranscodeSegment::getStatus, "PENDING", "RUNNING"));
            if (inFlight != null && inFlight > 0) {
                continue;
            }
            Long any = segmentMapper.selectCount(new LambdaQueryWrapper<TranscodeSegment>()
                    .eq(TranscodeSegment::getJobId, job.getId()));
            if (any == null || any == 0) {
                continue;
            }
            log.info("job {} WAITING with all segments settled, redispatch to converge", job.getId());
            dispatchAsync("job-converge-" + job.getVideoId(),
                    () -> transcodeService.process(job.getVideoId(), job.getObjectKey()));
        }
    }

    private void dispatchAsync(String name, Runnable task) {
        Thread.ofVirtual().name(name).start(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("reaper dispatch {} failed: {}", name, e.getMessage());
            }
        });
    }
}
