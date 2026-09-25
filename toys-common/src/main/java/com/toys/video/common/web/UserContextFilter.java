package com.toys.video.common.web;

import com.toys.video.common.constant.Headers;
import com.toys.video.common.context.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 从网关透传头装载用户身份到 UserContext。仅信任网关所在的请求链路。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class UserContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String userId = request.getHeader(Headers.USER_ID);
            String role = request.getHeader(Headers.USER_ROLE);
            if (userId != null && !userId.isBlank()) {
                UserContext.set(Long.valueOf(userId), role);
            }
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
