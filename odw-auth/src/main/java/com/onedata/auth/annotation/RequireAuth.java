package com.onedata.auth.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 需要用户认证的方法标记注解
 * 标记了此注解的方法要求当前请求已存在登录会话（UserContextHolder 已被 AuthenticationFilter 填充）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuth {

    /**
     * 是否必须有用户信息，默认为true
     * 如果为true且当前线程没有用户上下文，则返回401
     */
    boolean required() default true;
}
