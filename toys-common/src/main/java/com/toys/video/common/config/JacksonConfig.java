package com.toys.video.common.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置:装箱 Long 统一序列化为字符串(雪花 ID 超出 JS Number.MAX_SAFE_INTEGER,
 * 以数字输出会被前端截断丢精度)。计数类字段一律声明为原生 long,输出数字供前端运算。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
    }
}
