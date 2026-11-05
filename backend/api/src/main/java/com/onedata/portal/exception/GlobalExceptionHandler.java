package com.onedata.portal.exception;

import com.onedata.portal.dto.Result;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.concurrent.TimeoutException;

/** 全局异常处理器 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.onedata.portal.service.permission.PermissionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handlePermissionConflict(
            com.onedata.portal.service.permission.PermissionConflictException e) {
        return Result.fail(e.getMessage());
    }

    /** Preserve MVC routing status when a removed route reaches Boot's resource handler. */
    @ExceptionHandler({
        NoResourceFoundException.class,
        NoHandlerFoundException.class,
        HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<Result<Void>> handleRoutingException(Exception exception) {
        ErrorResponse error = (ErrorResponse) exception;
        int status = error.getStatusCode().value();
        return new ResponseEntity<>(
                Result.fail(status, status == 404 ? "接口不存在" : "请求方法不支持"),
                error.getHeaders(),
                error.getStatusCode());
    }

    /** 处理业务异常 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /** 处理运行时异常 */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception", e);
        return Result.fail("操作失败: " + e.getMessage());
    }

    /** 处理数据库重复键异常 */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleDuplicateKeyException(DuplicateKeyException e) {
        log.warn("Duplicate key exception: {}", e.getMessage());
        String message = "数据已存在";
        if (e.getMessage() != null) {
            if (e.getMessage().contains("uk_task_code")) {
                message = "任务编码已存在";
            } else if (e.getMessage().contains("uk_table_name")) {
                message = "表名已存在";
            }
        }
        return Result.fail(message);
    }

    /** 处理 Dolphin RestClient 响应异常 */
    @ExceptionHandler(RestClientResponseException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Result<Void> handleRestClientResponseException(RestClientResponseException e) {
        log.error(
                "Failed to call DolphinScheduler: status={}, body={}",
                e.getStatusCode(),
                e.getResponseBodyAsString(),
                e);
        return Result.fail("调用 DolphinScheduler OpenAPI 失败: " + e.getMessage());
    }

    /** 处理超时异常 */
    @ExceptionHandler(TimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Result<Void> handleTimeoutException(TimeoutException e) {
        log.error("Timeout exception", e);
        return Result.fail("操作超时，请稍后重试");
    }

    /** 处理非法参数异常 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return Result.fail("参数错误: " + e.getMessage());
    }

    /** 处理非法状态异常 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleIllegalStateException(IllegalStateException e) {
        log.error("Illegal state exception", e);
        return Result.fail("系统状态异常: " + e.getMessage());
    }

    /** 处理其他所有异常 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return Result.fail("系统错误，请联系管理员");
    }

    @ExceptionHandler(com.onedata.auth.exception.UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> unauthorized(com.onedata.auth.exception.UnauthorizedException e) {
        return Result.fail(401, e.getMessage());
    }

    @ExceptionHandler(com.onedata.auth.exception.ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> forbidden(com.onedata.auth.exception.ForbiddenException e) {
        return Result.fail(403, e.getMessage());
    }

    @ExceptionHandler(com.onedata.auth.exception.AuthFailureException.class)
    public Result<Void> authFailure(com.onedata.auth.exception.AuthFailureException e) {
        return Result.fail(400, e.getMessage());
    }
}
