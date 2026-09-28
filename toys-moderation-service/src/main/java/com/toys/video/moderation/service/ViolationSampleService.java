package com.toys.video.moderation.service;

import com.toys.video.moderation.entity.ViolationSample;
import com.toys.video.moderation.mapper.ViolationSampleMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/** 黑样本中心:内存持全量样本(库小全量加载),定时刷新 + 写操作后立即重载,机审匹配走内存。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationSampleService {

    private final ViolationSampleMapper sampleMapper;

    /** 当前快照:不可变列表,单引用整体替换。 */
    private volatile List<ViolationSample> samples = List.of();

    /** 首次启动即加载样本。 */
    @PostConstruct
    public void initialLoad() {
        reload();
    }

    /** 当前全量样本(可能为空列表,不会为 null)。 */
    public List<ViolationSample> current() {
        return samples;
    }

    /** 定时全量刷新:样本表量级小,直接重载;失败保留当前快照。 */
    @Scheduled(fixedDelay = 60_000)
    public void refreshPeriodically() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("violation samples refresh failed, keep current: {}", e.getMessage());
        }
    }

    /** 全量加载并原子替换快照(管理端写操作后调用)。 */
    public void reload() {
        List<ViolationSample> rows = sampleMapper.selectList(null);
        samples = List.copyOf(rows);
        log.info("violation samples loaded: {}", samples.size());
    }
}
