package com.toys.video.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toys.video.api.feign.VideoInternalClient;
import com.toys.video.media.entity.TranscodeJob;
import com.toys.video.media.mapper.TranscodeJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动自愈:把上次停机遗留的 RUNNING 转码作业标记为失败,交由 MQ 重投递。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupRecovery implements ApplicationRunner {

    private final TranscodeJobMapper jobMapper;
    private final VideoInternalClient videoInternalClient;

    @Override
    public void run(ApplicationArguments args) {
        List<TranscodeJob> running = jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getStatus, "RUNNING"));
        for (TranscodeJob job : running) {
            jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                    .eq(TranscodeJob::getId, job.getId())
                    .set(TranscodeJob::getStatus, "FAILED")
                    .set(TranscodeJob::getError, "服务重启中断,等待重试"));
            markVideoFailed(job.getVideoId());
            log.info("startup recovery: video {} job RUNNING -> FAILED", job.getVideoId());
        }
    }

    /** 视频仍停在 TRANSCODING 时回写 TRANSCODE_FAILED;其他状态(尚未置 TRANSCODING 等)不动。 */
    private void markVideoFailed(Long videoId) {
        try {
            var resp = videoInternalClient.batch(List.of(videoId));
            if (resp == null || resp.code() != 0 || resp.data() == null || resp.data().isEmpty()) {
                return;
            }
            if ("TRANSCODING".equals(resp.data().get(0).status())) {
                videoInternalClient.updateStatus(videoId,
                        new VideoInternalClient.InternalStatusUpdate("TRANSCODE_FAILED", null, null, null,
                                "服务重启中断,请重试"));
            }
        } catch (Exception e) {
            log.warn("startup recovery callback failed for video {}", videoId, e);
        }
    }
}
