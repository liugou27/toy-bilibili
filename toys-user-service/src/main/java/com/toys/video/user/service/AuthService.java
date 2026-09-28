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
    private final UsernameBloomService usernameBloom;
    private final PunishService punishService;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    @org.springframework.beans.factory.annotation.Value("${toys.jwt.ttl-seconds:604800}")
    private long jwtTtlSeconds;
    private final Map<String, RegisterWindow> registerWindows = new ConcurrentHashMap<>();

    public LoginResponse register(RegisterRequest req, String clientIp) {
        checkRegisterAllowed(clientIp);
        // 布隆快路径:false 必然未占用,跳过 DB 查询;true 仍查 DB 确认,唯一约束是最终兜底
        if (usernameBloom.mightContain(req.username())
                && userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, req.username())) > 0) {
            throw BizException.of(ErrorCode.USERNAME_TAKEN);
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole("USER");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw BizException.of(ErrorCode.USERNAME_TAKEN);
        }
        usernameBloom.add(user.getUsername());
        String token = jwtUtil.issue(user.getId(), user.getRole());
        registerToken(token);
        return new LoginResponse(token, toInfo(user));
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
        // 封禁拦截放在凭证校验之后:避免向持有错误密码的请求泄露封禁状态
        punishService.checkLoginAllowed(user);
        String token = jwtUtil.issue(user.getId(), user.getRole());
        registerToken(token);
        return new LoginResponse(token, toInfo(user));
    }

    /** 注销:从服务端删除 jti,token 立即失效(黑名单语义的Reverse——白名单)。 */
    public void logout(String token) {
        try {
            String jti = jwtUtil.parse(token).jti();
            redis.delete("auth:token:" + jti);
        } catch (Exception e) {
            // 无效 token 的注销静默成功,不泄露信息
        }
    }

    /** 修改密码:校验旧密码,更新后注销当前 token,强制重新登录。 */
    public void changePassword(Long userId, String oldPassword, String newPassword, String currentToken) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "原密码不正确");
        }
        User update = new User();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);
        // 改密踢出该用户全部设备:逐个删除 jti 白名单成员
        try {
            String userKey = "auth:user:" + userId;
            java.util.Set<String> jtis = redis.opsForSet().members(userKey);
            if (jtis != null) {
                jtis.forEach(jti -> redis.delete("auth:token:" + jti));
            }
            redis.delete(userKey);
        } catch (Exception e) {
            log.warn("kick all sessions failed, fallback to current token only: {}", e.getMessage());
            logout(currentToken);
        }
    }

    /** 登记签发的 token(jti 白名单)并维护 per-user 会话集合(改密时全量踢出)。
     * Redis 故障时抛错使登录整体失败:否则签出的 token 不在白名单,网关会判为已注销。 */
    private void registerToken(String token) {
        com.toys.video.common.security.JwtUtil.TokenPayload payload;
        try {
            payload = jwtUtil.parse(token);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "登录服务异常,请重试");
        }
        try {
            redis.opsForValue().set("auth:token:" + payload.jti(), "1",
                    java.time.Duration.ofSeconds(jwtTtlSeconds));
            String userKey = "auth:user:" + payload.userId();
            redis.opsForSet().add(userKey, payload.jti());
            redis.expire(userKey, java.time.Duration.ofSeconds(jwtTtlSeconds));
        } catch (Exception e) {
            log.error("register token failed", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "登录服务异常,请重试");
        }
    }

    /** 编辑资料:仅登录本人;nickname/avatar 传 null 表示不修改,非 null 则覆盖(空串视为清空)。 */
    public UserInfo updateProfile(Long userId, String nickname, String avatar) {
        if (userMapper.selectById(userId) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        User update = new User();
        update.setId(userId);
        if (nickname != null) {
            update.setNickname(nickname);
        }
        if (avatar != null) {
            update.setAvatar(avatar);
        }
        userMapper.updateById(update);
        return me(userId);
    }

    public UserInfo me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toInfo(user);
    }

    @org.springframework.beans.factory.annotation.Value("${toys.user.seed-admin:true}")
    private boolean seedAdmin;

    /** 幂等种子:本地/实验环境的管理员账号,生产经 toys.user.seed-admin=false 关闭。 */
    @Override
    public void run(ApplicationArguments args) {
        if (!seedAdmin) {
            return;
        }
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
        usernameBloom.add(SEED_ADMIN_USERNAME);
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
        return new UserInfo(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole());
    }

    /** 注册防刷窗口:起点与窗口内尝试次数。 */
    private record RegisterWindow(long windowStartMillis, int count) {
    }
}
