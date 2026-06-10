package com.onedata.auth.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 需要指定角色的方法标记注解
 * 要求当前会话用户角色与 value 一致，否则返回403
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 要求的角色，如 admin
     */
    String value();
}
