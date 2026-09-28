package com.toys.video.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.dto.PunishStatus;
import com.toys.video.user.entity.User;
import com.toys.video.user.entity.UserViolation;
import com.toys.video.user.mapper.UserMapper;
import com.toys.video.user.mapper.UserViolationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/** 违规处罚:记录违规、判定处罚态、登录拦截。 */
@Service
@RequiredArgsConstructor
public class PunishService {

    /** 违规累计达到该次数后永久封禁。 */
    public static final int BAN_THRESHOLD = 5;

    /** 违规累计达到该次数进入禁言判定的下限。 */
    public static final int MUTE_THRESHOLD = 3;

    /** 禁言时长:自最近一次违规时间起算。 */
    public static final int MUTE_DAYS = 7;

    /** 允许的违规类型,与跨服务契约保持一致。 */
    private static final Set<String> VIOLATION_TYPES = Set.of("POLITIC", "PORN", "VULGAR", "AD", "OTHER");

    private final UserMapper userMapper;
    private final UserViolationMapper violationMapper;

    /** 处罚判定(纯函数):
     *  - 违规累计 ≥5 → banned(永久);
     *  - 累计 ≥3 且 <5 → 最近一次违规时间 +7 天内 muted,期满自动解除;
     *  - 手动封禁(banned_until 未过期)→ banned;
     *  - banned 优先于 muted。 */
    public static PunishStatus evaluate(long violationCount, LocalDateTime lastViolationAt,
                                        LocalDateTime bannedUntil, LocalDateTime now) {
        boolean banned = violationCount >= BAN_THRESHOLD
                || (bannedUntil != null && bannedUntil.isAfter(now));
        boolean muted = !banned
                && violationCount >= MUTE_THRESHOLD
                && lastViolationAt != null
                && lastViolationAt.plusDays(MUTE_DAYS).isAfter(now);
        return new PunishStatus(muted, banned, violationCount);
    }

    /** 记录一条违规并返回最新处罚状态。 */
    public PunishStatus reportViolation(Long userId, String type, String reason, Long videoId) {
        if (userId == null || !VIOLATION_TYPES.contains(type)) {
            throw BizException.of(ErrorCode.PARAM_INVALID);
        }
        if (userMapper.selectById(userId) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        UserViolation violation = new UserViolation();
        violation.setUserId(userId);
        violation.setType(type);
        violation.setReason(reason);
        violation.setVideoId(videoId);
        violation.setCreatedAt(LocalDateTime.now());
        violationMapper.insert(violation);
        return statusOf(userId);
    }

    /** 查询用户当前处罚状态。 */
    public PunishStatus statusOf(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return evaluate(countViolations(userId), lastViolationAt(userId), user.getBannedUntil(),
                LocalDateTime.now());
    }

    /** 登录拦截:封禁(阈值或手动)抛 FORBIDDEN;禁言不影响登录。 */
    public void checkLoginAllowed(User user) {
        boolean banned = evaluate(countViolations(user.getId()), lastViolationAt(user.getId()),
                user.getBannedUntil(), LocalDateTime.now()).banned();
        if (banned) {
            throw BizException.of(ErrorCode.FORBIDDEN, "账号已被封禁");
        }
    }

    private long countViolations(Long userId) {
        Long count = violationMapper.selectCount(new LambdaQueryWrapper<UserViolation>()
                .eq(UserViolation::getUserId, userId));
        return count == null ? 0L : count;
    }

    private LocalDateTime lastViolationAt(Long userId) {
        UserViolation last = violationMapper.selectOne(new LambdaQueryWrapper<UserViolation>()
                .eq(UserViolation::getUserId, userId)
                .orderByDesc(UserViolation::getCreatedAt)
                .last("LIMIT 1"));
        return last == null ? null : last.getCreatedAt();
    }
}
