package com.onedata.portal.exception;

import com.onedata.portal.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.onedata.portal.service.permission.PermissionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handlePermissionConflict(com.onedata.portal.service.permission.PermissionConflictException e) {
        return Result.fail(e.getMessage());
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 处理运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception", e);
        return Result.fail("操作失败: " + e.getMessage());
    }

    /**
     * 处理数据库重复键异常
     */
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

    /**
     * 处理 WebClient 响应异常 (调用 Python 服务失败)
     */
    @ExceptionHandler(WebClientResponseException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Result<Void> handleWebClientResponseException(WebClientResponseException e) {
        log.error("Failed to call DolphinScheduler: status={}, body={}",
            e.getStatusCode(), e.getResponseBodyAsString(), e);
        return Result.fail("调用 DolphinScheduler OpenAPI 失败: " + e.getMessage());
    }

    /**
     * 处理超时异常
     */
    @ExceptionHandler(TimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Result<Void> handleTimeoutException(TimeoutException e) {
        log.error("Timeout exception", e);
        return Result.fail("操作超时，请稍后重试");
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return Result.fail("参数错误: " + e.getMessage());
    }

    /**
     * 处理非法状态异常
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleIllegalStateException(IllegalStateException e) {
        log.error("Illegal state exception", e);
        return Result.fail("系统状态异常: " + e.getMessage());
    }

    /**
     * 处理其他所有异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return Result.fail("系统错误，请联系管理员");
    }
}
