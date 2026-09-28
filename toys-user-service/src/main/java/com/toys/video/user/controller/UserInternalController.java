package com.toys.video.user.controller;

import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.dto.PunishStatus;
import com.toys.video.user.dto.ViolationReport;
import com.toys.video.user.entity.User;
import com.toys.video.user.mapper.UserMapper;
import com.toys.video.user.service.PunishService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class UserInternalController {

    private final UserMapper userMapper;
    private final PunishService punishService;

    @GetMapping("/batch")
    public R<List<UserBrief>> batch(@RequestParam List<Long> ids, HttpServletRequest request) {
        requireInternal(request);
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            return R.ok(List.of());
        }
        return R.ok(userMapper.selectBatchIds(ids).stream()
                .map(u -> new UserBrief(u.getId(), u.getUsername(), u.getNickname()))
                .toList());
    }

    /** 记录一条违规并返回最新处罚状态。 */
    @PostMapping("/violations")
    public R<PunishStatus> reportViolation(@Valid @RequestBody ViolationReport req,
                                           HttpServletRequest request) {
        requireInternal(request);
        return R.ok(punishService.reportViolation(req.userId(), req.type(), req.reason(), req.videoId()));
    }

    /** 查询用户处罚状态,供下游做禁言/封禁拦截。 */
    @GetMapping("/{id}/punish")
    public R<PunishStatus> punish(@PathVariable Long id, HttpServletRequest request) {
        requireInternal(request);
        return R.ok(punishService.statusOf(id));
    }

    public record UserBrief(Long id, String username, String nickname) {
    }

    private void requireInternal(HttpServletRequest request) {
        if (!"1".equals(request.getHeader(Headers.INTERNAL_CALL))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
}
