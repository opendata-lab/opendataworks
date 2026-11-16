package com.onedata.auth.exception;

/** OAuth 用户开通或账号状态校验失败异常，携带用于前端错误提示的错误码 */
public class OAuthProvisioningException extends RuntimeException {

    private final String errorCode;

    public OAuthProvisioningException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OAuthProvisioningException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
