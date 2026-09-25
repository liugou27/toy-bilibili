package com.toys.video.user.controller;

import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.user.entity.User;
import com.toys.video.user.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class UserInternalController {

    private final UserMapper userMapper;

    @GetMapping("/batch")
    public R<List<UserBrief>> batch(@RequestParam List<Long> ids, HttpServletRequest request) {
        if (!"1".equals(request.getHeader(Headers.INTERNAL_CALL))) {
            throw new com.toys.video.common.exception.BizException(
                    com.toys.video.common.exception.ErrorCode.FORBIDDEN);
        }
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            return R.ok(List.of());
        }
        return R.ok(userMapper.selectBatchIds(ids).stream()
                .map(u -> new UserBrief(u.getId(), u.getUsername()))
                .toList());
    }

    public record UserBrief(Long id, String username) {
    }
}
