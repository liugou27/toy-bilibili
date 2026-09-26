package com.toys.video.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toys.video.common.util.BloomFilter;
import com.toys.video.user.entity.User;
import com.toys.video.user.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户名占用布隆过滤器:注册查重的快路径。
 * mightContain=false 必然未占用,跳过 DB 查询;true 时仍查 DB 确认,users.username 唯一约束是最终兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsernameBloomService {

    private static final double FALSE_POSITIVE_RATE = 0.01;
    /** 饱和度超过该阈值触发重建。 */
    private static final double REBUILD_SATURATION = 0.5;
    /** 全量扫描每批拉取的行数。 */
    private static final int SCAN_BATCH_SIZE = 1000;

    private final UserMapper userMapper;

    @Value("${toys.user.bloom-capacity:100000}")
    private long capacity;

    /** 当前过滤器;volatile 保证重建后其他线程立即看到完整的新实例。 */
    private volatile BloomFilter filter;

    /** 启动时全量扫描 users.username 构建过滤器;失败则保持为空,注册退化为纯 DB 查重。 */
    @PostConstruct
    public void init() {
        long start = System.currentTimeMillis();
        BloomFilter built = scanAndLoad(capacity);
        if (built == null) {
            log.warn("username bloom filter build failed, register falls back to db-only check");
            return;
        }
        filter = built;
        log.info("username bloom filter built: capacity={}, insertions={}, saturation={}, cost={}ms",
                capacity, built.insertions(), built.saturation(), System.currentTimeMillis() - start);
    }

    /** 每 5 分钟检查饱和度,超过阈值即重建:容量翻倍 → 全量用户名重扫迁移 → 原子替换。 */
    @Scheduled(fixedDelay = 300_000)
    public void rebuildWhenSaturated() {
        BloomFilter current = filter;
        if (current == null || current.saturation() <= REBUILD_SATURATION) {
            return;
        }
        long newCapacity = current.expectedInsertions() * 2;
        long start = System.currentTimeMillis();
        BloomFilter rebuilt = scanAndLoad(newCapacity);
        if (rebuilt == null) {
            // 扫描异常:保留旧过滤器继续服务,下个周期重试
            log.warn("username bloom filter rebuild failed, keep the old one: capacity={}, saturation={}",
                    current.expectedInsertions(), current.saturation());
            return;
        }
        filter = rebuilt;
        log.info("username bloom filter rebuilt: capacity {} -> {}, insertions={}, saturation={}, cost={}ms",
                current.expectedInsertions(), newCapacity, rebuilt.insertions(), rebuilt.saturation(),
                System.currentTimeMillis() - start);
    }

    /** 注册预检:返回 false 必然未占用;true 时调用方仍须查 DB 确认。 */
    public boolean mightContain(String username) {
        BloomFilter current = filter;
        return current != null && current.mightContain(username);
    }

    /** 注册成功后登记新用户名。 */
    public void add(String username) {
        BloomFilter current = filter;
        if (current != null) {
            current.add(username);
        }
    }

    /** 当前过滤器实例,供测试与运维观察。 */
    BloomFilter current() {
        return filter;
    }

    /** 分批全量扫描用户名装入容量为 expectedInsertions 的新过滤器;任一批失败返回 null。 */
    private BloomFilter scanAndLoad(long expectedInsertions) {
        BloomFilter fresh = new BloomFilter(expectedInsertions, FALSE_POSITIVE_RATE);
        try {
            long lastId = 0L;
            while (true) {
                List<User> batch = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .select(User::getId, User::getUsername)
                        .gt(User::getId, lastId)
                        .orderByAsc(User::getId)
                        .last("limit " + SCAN_BATCH_SIZE));
                if (batch.isEmpty()) {
                    return fresh;
                }
                for (User user : batch) {
                    fresh.add(user.getUsername());
                    lastId = user.getId();
                }
            }
        } catch (Exception e) {
            log.warn("scan usernames for bloom filter failed: {}", e.getMessage());
            return null;
        }
    }
}
