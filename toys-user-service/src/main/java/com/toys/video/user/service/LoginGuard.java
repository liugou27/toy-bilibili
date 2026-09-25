package com.toys.video.user.service;

import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防爆破(单机内存版:user-service 未引入 Redis 依赖,以 ConcurrentHashMap + 时间戳实现同等语义;
 * 多实例部署时各节点独立计数)。
 * 规则:同一用户名 5 分钟窗口内连续失败 ≥5 次,则 15 分钟内拒绝登录;登录成功清除计数。
 */
@Component
public class LoginGuard {

    private static final int MAX_FAILURES = 5;
    private static final long FAILURE_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final long BLOCK_MILLIS = 15 * 60 * 1000L;
    /** 计数表超过该大小时触发一次过期清理,防止长期运行内存膨胀。 */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Map<String, FailState> failCounters = new ConcurrentHashMap<>();

    /** 在密码比对前调用:该用户名处于封锁期则直接拒绝。 */
    public void checkLoginAllowed(String username) {
        FailState state = failCounters.get(username);
        if (state != null && state.blockedUntilMillis() > System.currentTimeMillis()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "失败次数过多,请 15 分钟后再试");
        }
    }

    /** 登录失败时调用:窗口内连续失败计数 +1,达到阈值后封锁 15 分钟。 */
    public void recordFailure(String username) {
        long now = System.currentTimeMillis();
        failCounters.compute(username, (k, prev) -> {
            if (prev == null || now - prev.windowStartMillis() > FAILURE_WINDOW_MILLIS) {
                return new FailState(now, 1, 0L);
            }
            int count = prev.failureCount() + 1;
            long blockedUntil = count >= MAX_FAILURES ? now + BLOCK_MILLIS : prev.blockedUntilMillis();
            return new FailState(prev.windowStartMillis(), count, blockedUntil);
        });
        if (failCounters.size() > SWEEP_THRESHOLD) {
            sweepExpired(now);
        }
    }

    /** 登录成功时调用:清除该用户名的失败计数。 */
    public void clear(String username) {
        failCounters.remove(username);
    }

    private void sweepExpired(long now) {
        failCounters.entrySet().removeIf(e -> {
            FailState s = e.getValue();
            return now - s.windowStartMillis() > FAILURE_WINDOW_MILLIS && s.blockedUntilMillis() <= now;
        });
    }

    /** 失败计数状态:失败窗口起点、窗口内失败次数、封锁截止时间(0 表示未封锁)。 */
    private record FailState(long windowStartMillis, int failureCount, long blockedUntilMillis) {
    }
}
