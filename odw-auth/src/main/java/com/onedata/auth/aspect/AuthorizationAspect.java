package com.onedata.auth.aspect;

import com.onedata.auth.annotation.RequireAuth;
import com.onedata.auth.annotation.RequireRole;
import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.auth.exception.ForbiddenException;
import com.onedata.auth.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * 方法级授权切面
 * 会话解析由 AuthenticationFilter 完成；本切面只读 UserContextHolder 做强制要求与角色校验
 */
@Slf4j
@Aspect
public class AuthorizationAspect {

    @Before("@annotation(com.onedata.auth.annotation.RequireAuth)")
    public void checkAuthenticated(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequireAuth requireAuth = signature.getMethod().getAnnotation(RequireAuth.class);
        if (requireAuth.required() && !UserContextHolder.isAuthenticated()) {
            log.warn("Unauthenticated access to {}", signature.toShortString());
            throw new UnauthorizedException("用户未认证");
        }
    }

    @Before("@annotation(com.onedata.auth.annotation.RequireRole)")
    public void checkRole(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequireRole requireRole = signature.getMethod().getAnnotation(RequireRole.class);
        UserContext context = UserContextHolder.getContext();
        if (context == null || context.getUserId() == null) {
            throw new UnauthorizedException("用户未认证");
        }
        if (!requireRole.value().equals(context.getRole())) {
            log.warn("User {} lacks role {} for {}", context.getUsername(), requireRole.value(),
                    signature.toShortString());
            throw new ForbiddenException("权限不足");
        }
    }
}
