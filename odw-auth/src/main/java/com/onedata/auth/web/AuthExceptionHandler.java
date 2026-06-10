package com.onedata.auth.web;

import com.onedata.auth.exception.AuthFailureException;
import com.onedata.auth.exception.ForbiddenException;
import com.onedata.auth.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 认证模块异常映射
 * 优先级高于宿主的 RuntimeException 兜底处理，确保 401/403 语义不被吞掉
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorized(UnauthorizedException e) {
        return ApiResponse.fail(401, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleForbidden(ForbiddenException e) {
        return ApiResponse.fail(403, e.getMessage());
    }

    @ExceptionHandler(AuthFailureException.class)
    public ApiResponse<Void> handleAuthFailure(AuthFailureException e) {
        log.info("Auth failure: {}", e.getMessage());
        return ApiResponse.fail(400, e.getMessage());
    }
}
