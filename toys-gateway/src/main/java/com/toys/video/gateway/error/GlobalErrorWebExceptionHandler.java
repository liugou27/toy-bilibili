package com.toys.video.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toys.video.common.api.R;
import com.toys.video.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeoutException;

/** 网关统一错误出口:下游不可用/超时返回结构化 JSON,而不是连接错误裸页。 */
@Component
@Order(-2)
public class GlobalErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorWebExceptionHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        ErrorCode errorCode = resolve(ex);
        HttpStatus status = switch (errorCode) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REQUEST_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.warn("gateway error: path={}, type={}, code={}",
                exchange.getRequest().getURI().getPath(), ex.getClass().getSimpleName(), errorCode.getCode());

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(R.fail(errorCode.getCode(), errorCode.getMessage()));
        } catch (Exception e) {
            body = "{\"code\":5000,\"message\":\"internal error\",\"data\":null}".getBytes();
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private ErrorCode resolve(Throwable ex) {
        if (ex instanceof org.springframework.web.reactive.resource.NoResourceFoundException) {
            return ErrorCode.NOT_FOUND;
        }
        if (ex instanceof NotFoundException) {
            return ErrorCode.SERVICE_UNAVAILABLE;
        }
        if (ex instanceof TimeoutException) {
            return ErrorCode.REQUEST_TIMEOUT;
        }
        Throwable cause = ex.getCause();
        if (cause != null) {
            if (cause instanceof NotFoundException) {
                return ErrorCode.SERVICE_UNAVAILABLE;
            }
            if (cause instanceof TimeoutException) {
                return ErrorCode.REQUEST_TIMEOUT;
            }
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
