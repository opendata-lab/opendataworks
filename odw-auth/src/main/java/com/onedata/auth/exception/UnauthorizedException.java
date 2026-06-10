package com.onedata.auth.exception;

/**
 * 未认证异常，统一映射为 HTTP 401
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
