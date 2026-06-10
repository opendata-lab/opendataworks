package com.onedata.auth.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证模块统一响应包装
 * 与宿主平台 Result 的 {code, message, data} 契约保持一致，便于前端统一处理
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private Integer code;

    private String message;

    private T data;

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "success", null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> fail(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return fail(500, message);
    }
}
