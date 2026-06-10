package com.onedata.auth.exception;

/**
 * 权限不足异常，统一映射为 HTTP 403
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
