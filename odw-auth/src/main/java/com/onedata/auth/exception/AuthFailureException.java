package com.onedata.auth.exception;

/**
 * 登录失败异常（口令错误、账号锁定、账号停用等），统一映射为 HTTP 200 + 业务错误码
 * 与平台前端 request.js 的 {code, message, data} 错误处理约定保持一致
 */
public class AuthFailureException extends RuntimeException {

    public AuthFailureException(String message) {
        super(message);
    }
}
