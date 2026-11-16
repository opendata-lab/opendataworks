package com.onedata.auth.web;

import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.auth.exception.UnauthorizedException;
import com.onedata.auth.jwt.SessionCookieIssuer;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserService;
import com.onedata.portal.dto.Result;

import jakarta.servlet.http.HttpServletResponse;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 认证接口（路径位于白名单内，登录态要求在各端点内自行判断） */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService sysUserService;
    private final SessionCookieIssuer sessionCookieIssuer;

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(
            @RequestBody LoginRequest request, HttpServletResponse response) {
        SysUser user = sysUserService.login(request.getUsername(), request.getPassword());
        CurrentPrincipal context = SessionCookieIssuer.toPrincipal(user);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieIssuer.issueCookie(context));
        log.info("User logged in: {}", user.getUsername());
        return Result.success(toUserInfo(context, user));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieIssuer.clearCookie());
        return Result.success();
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        CurrentPrincipal context = requireCurrentUser();
        SysUser user =
                parseUserId(context.getUserId()) != null
                        ? sysUserService.findById(parseUserId(context.getUserId()))
                        : null;
        return Result.success(toUserInfo(context, user));
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        CurrentPrincipal context = requireCurrentUser();
        Long userId = parseUserId(context.getUserId());
        if (userId == null) {
            throw new UnauthorizedException("当前会话不支持修改密码");
        }
        sysUserService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
        return Result.success();
    }

    private CurrentPrincipal requireCurrentUser() {
        CurrentPrincipal context = PrincipalContextHolder.getContext();
        if (context == null || context.getUserId() == null) {
            throw new UnauthorizedException("未登录或会话已过期");
        }
        return context;
    }

    private Long parseUserId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> toUserInfo(CurrentPrincipal context, SysUser user) {
        String nickname = user != null ? user.getNickname() : null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", context.getUserId());
        info.put("username", context.getUsername());
        info.put("nickname", nickname != null ? nickname : context.getUsername());
        info.put("role", context.getRole());
        info.put("authSource", context.getAuthSource());
        return info;
    }
}
