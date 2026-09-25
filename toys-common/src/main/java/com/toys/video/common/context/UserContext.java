package com.toys.video.common.context;

/** 当前登录用户(由网关鉴权后透传,UserContextFilter 装载)。 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId, String role) {
        USER_ID.set(userId);
        USER_ROLE.set(role);
    }

    public static Long userId() {
        return USER_ID.get();
    }

    public static String userRole() {
        return USER_ROLE.get();
    }

    public static void clear() {
        USER_ID.remove();
        USER_ROLE.remove();
    }
}
