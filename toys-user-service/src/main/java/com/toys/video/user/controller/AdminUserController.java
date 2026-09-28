package com.toys.video.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.dto.AdminUserItem;
import com.toys.video.user.dto.PunishStatus;
import com.toys.video.user.entity.User;
import com.toys.video.user.entity.UserViolation;
import com.toys.video.user.mapper.UserMapper;
import com.toys.video.user.mapper.UserViolationMapper;
import com.toys.video.user.service.PunishService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 后台用户管理:搜索 / 封禁 / 解封。网关已校验 ADMIN 角色,这里二次校验。 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    /** 手动封禁时长:100 年,事实上的永久。 */
    private static final long MANUAL_BAN_YEARS = 100;

    private final UserMapper userMapper;
    private final UserViolationMapper violationMapper;

    /** 用户搜索:用户名模糊,附违规累计与处罚态。 */
    @GetMapping
    public R<PageResult<AdminUserItem>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        requireAdmin();
        IPage<User> p = userMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                new LambdaQueryWrapper<User>()
                        .like(StringUtils.hasText(keyword), User::getUsername, keyword.trim())
                        .orderByDesc(User::getCreatedAt));
        Map<Long, List<UserViolation>> byUser = groupViolations(p.getRecords().stream()
                .map(User::getId).toList());
        LocalDateTime now = LocalDateTime.now();
        List<AdminUserItem> items = p.getRecords().stream()
                .map(u -> {
                    List<UserViolation> violations = byUser.getOrDefault(u.getId(), List.of());
                    LocalDateTime lastAt = violations.stream()
                            .map(UserViolation::getCreatedAt)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    PunishStatus status = PunishService.evaluate(violations.size(), lastAt,
                            u.getBannedUntil(), now);
                    return new AdminUserItem(u.getId(), u.getUsername(), u.getNickname(), u.getRole(),
                            status.violationCount(), status.muted(), status.banned());
                })
                .toList();
        return R.ok(new PageResult<>(items, p.getTotal(), p.getCurrent(), p.getSize()));
    }

    /** 手动封禁:banned_until = now + 100 年。 */
    @PostMapping("/{id}/ban")
    public R<Void> ban(@PathVariable Long id) {
        requireAdmin();
        requireUser(id);
        User update = new User();
        update.setId(id);
        update.setBannedUntil(LocalDateTime.now().plusYears(MANUAL_BAN_YEARS));
        userMapper.updateById(update);
        return R.ok();
    }

    /** 手动解封:banned_until 置空(阈值封禁不因此解除)。 */
    @PostMapping("/{id}/unban")
    public R<Void> unban(@PathVariable Long id) {
        requireAdmin();
        requireUser(id);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .set(User::getBannedUntil, null));
        return R.ok();
    }

    private Map<Long, List<UserViolation>> groupViolations(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return violationMapper.selectList(new LambdaQueryWrapper<UserViolation>()
                        .in(UserViolation::getUserId, userIds))
                .stream()
                .collect(Collectors.groupingBy(UserViolation::getUserId));
    }

    private void requireUser(Long id) {
        if (userMapper.selectById(id) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(UserContext.userRole())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
}
