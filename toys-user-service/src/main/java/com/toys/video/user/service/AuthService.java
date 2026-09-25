package com.toys.video.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.security.JwtUtil;
import com.toys.video.user.dto.LoginRequest;
import com.toys.video.user.dto.LoginResponse;
import com.toys.video.user.dto.RegisterRequest;
import com.toys.video.user.dto.UserInfo;
import com.toys.video.user.entity.User;
import com.toys.video.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService implements ApplicationRunner {

    private static final String SEED_ADMIN_USERNAME = "admin";
    private static final String SEED_ADMIN_PASSWORD = "admin123";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LoginResponse register(RegisterRequest req) {
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole("USER");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw BizException.of(ErrorCode.USERNAME_TAKEN);
        }
        return new LoginResponse(jwtUtil.issue(user.getId(), user.getRole()), toInfo(user));
    }

    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.username()));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }
        return new LoginResponse(jwtUtil.issue(user.getId(), user.getRole()), toInfo(user));
    }

    public UserInfo me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toInfo(user);
    }

    /** 幂等种子:保证本地环境始终有可用的管理员账号 admin/admin123。 */
    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, SEED_ADMIN_USERNAME));
        if (count != null && count > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername(SEED_ADMIN_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(SEED_ADMIN_PASSWORD));
        admin.setRole("ADMIN");
        userMapper.insert(admin);
        log.info("seeded admin account '{}'", SEED_ADMIN_USERNAME);
    }

    private UserInfo toInfo(User user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getRole());
    }
}
