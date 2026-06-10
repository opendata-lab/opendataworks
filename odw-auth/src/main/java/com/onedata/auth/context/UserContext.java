package com.onedata.auth.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户上下文信息
 * 存储当前请求的用户身份信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    public static final String AUTH_SOURCE_LOCAL = "local";
    public static final String AUTH_SOURCE_ANONYMOUS = "anonymous";

    /**
     * 用户ID（对应 sys_user.id）
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 角色 admin/user
     */
    private String role;

    /**
     * 认证来源 local/oauth/anonymous
     */
    private String authSource;

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
