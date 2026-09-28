package com.toys.video.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.common.api.PageResult;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.dto.FollowItem;
import com.toys.video.user.dto.FollowStats;
import com.toys.video.user.entity.Follow;
import com.toys.video.user.entity.User;
import com.toys.video.user.mapper.FollowMapper;
import com.toys.video.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 关注体系:关注/取关幂等,关注列表、粉丝列表与统计。 */
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowMapper followMapper;
    private final UserMapper userMapper;

    /** 关注:禁止自关注;重复关注幂等成功,并发下由唯一约束兜底。 */
    public void follow(Long userId, Long targetId) {
        if (userId.equals(targetId)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "不能关注自己");
        }
        if (userMapper.selectById(targetId) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (exists(userId, targetId)) {
            return;
        }
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setTargetId(targetId);
        follow.setCreatedAt(LocalDateTime.now());
        try {
            followMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            // 并发重复关注:唯一约束命中,视为幂等成功
        }
    }

    /** 取关:未关注时同样幂等成功。 */
    public void unfollow(Long userId, Long targetId) {
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getTargetId, targetId));
    }

    /** TA 关注的人,按关注时间倒序。 */
    public PageResult<FollowItem> following(Long targetId, long page, long size) {
        requireUser(targetId);
        IPage<Follow> p = followMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, targetId)
                        .orderByDesc(Follow::getCreatedAt));
        return toItems(p, Follow::getTargetId);
    }

    /** TA 的粉丝,按关注时间倒序。 */
    public PageResult<FollowItem> fans(Long targetId, long page, long size) {
        requireUser(targetId);
        IPage<Follow> p = followMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getTargetId, targetId)
                        .orderByDesc(Follow::getCreatedAt));
        return toItems(p, Follow::getUserId);
    }

    /** 当前登录人是否关注了 TA,未登录返回 false。 */
    public boolean followed(Long currentUserId, Long targetId) {
        if (currentUserId == null) {
            return false;
        }
        return exists(currentUserId, targetId);
    }

    /** 关注统计:following=TA 关注的人数,follower=TA 的粉丝数。 */
    public FollowStats stats(Long targetId) {
        requireUser(targetId);
        Long following = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, targetId));
        Long follower = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getTargetId, targetId));
        return new FollowStats(following == null ? 0L : following, follower == null ? 0L : follower);
    }

    private boolean exists(Long userId, Long targetId) {
        Long count = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getTargetId, targetId));
        return count != null && count > 0;
    }

    private void requireUser(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    /** 关系行分页 → 展示条目:批量补齐对方资料,followedAt 取关系行建立时间。 */
    private PageResult<FollowItem> toItems(IPage<Follow> p, Function<Follow, Long> otherParty) {
        List<Long> otherIds = p.getRecords().stream().map(otherParty).distinct().toList();
        Map<Long, User> users = otherIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(otherIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        List<FollowItem> items = p.getRecords().stream()
                .map(row -> {
                    User u = users.get(otherParty.apply(row));
                    return u == null ? null
                            : new FollowItem(u.getId(), u.getUsername(), u.getNickname(), u.getAvatar(),
                                    row.getCreatedAt());
                })
                .filter(Objects::nonNull)
                .toList();
        return new PageResult<>(items, p.getTotal(), p.getCurrent(), p.getSize());
    }
}
