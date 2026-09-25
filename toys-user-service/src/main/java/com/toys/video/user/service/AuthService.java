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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService implements ApplicationRunner {

    private static final String SEED_ADMIN_USERNAME = "admin";
    private static final String SEED_ADMIN_PASSWORD = "admin123";

    /** 注册防刷(单机内存版):同一 IP 一个窗口内最多注册次数。 */
    private static final int REGISTER_LIMIT_PER_WINDOW = 5;
    private static final long REGISTER_WINDOW_MILLIS = 60 * 1000L;
    private static final int SWEEP_THRESHOLD = 10_000;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginGuard loginGuard;
    private final Map<String, RegisterWindow> registerWindows = new ConcurrentHashMap<>();

    public LoginResponse register(RegisterRequest req, String clientIp) {
        checkRegisterAllowed(clientIp);
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
        loginGuard.checkLoginAllowed(req.username());
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.username()));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            loginGuard.recordFailure(req.username());
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }
        loginGuard.clear(req.username());
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

    /** 同一 IP 在一个窗口内的注册尝试计数,超限直接拒绝(含被拒绝的尝试)。 */
    private void checkRegisterAllowed(String clientIp) {
        long now = System.currentTimeMillis();
        RegisterWindow window = registerWindows.compute(clientIp, (ip, prev) ->
                prev == null || now - prev.windowStartMillis() >= REGISTER_WINDOW_MILLIS
                        ? new RegisterWindow(now, 1)
                        : new RegisterWindow(prev.windowStartMillis(), prev.count() + 1));
        if (registerWindows.size() > SWEEP_THRESHOLD) {
            registerWindows.entrySet().removeIf(e -> now - e.getValue().windowStartMillis() >= REGISTER_WINDOW_MILLIS);
        }
        if (window.count() > REGISTER_LIMIT_PER_WINDOW) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "注册过于频繁");
        }
    }

    private UserInfo toInfo(User user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getRole());
    }

    /** 注册防刷窗口:起点与窗口内尝试次数。 */
    private record RegisterWindow(long windowStartMillis, int count) {
    }
}
