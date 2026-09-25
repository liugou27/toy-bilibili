package com.toys.video.user.config;

import com.toys.video.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {

    @Bean
    public JwtUtil jwtUtil(@Value("${toys.jwt.secret}") String secret,
                           @Value("${toys.jwt.ttl-seconds:604800}") long ttlSeconds) {
        return new JwtUtil(secret, ttlSeconds);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
