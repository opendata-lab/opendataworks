package com.onedata.auth.web;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.auth.exception.UnauthorizedException;
import com.onedata.auth.jwt.JwtService;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口（路径位于白名单内，登录态要求在各端点内自行判断）
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService sysUserService;
    private final JwtService jwtService;
    private final OdwAuthProperties properties;

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
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        SysUser user = sysUserService.login(request.getUsername(), request.getPassword());
        UserContext context = new UserContext(
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getRole(),
                user.getAuthSource());
        String token = jwtService.issue(context);
        response.addHeader(HttpHeaders.SET_COOKIE, buildSessionCookie(token, properties.getJwt().getTtl().getSeconds()));
        log.info("User logged in: {}", user.getUsername());
        return ApiResponse.success(toUserInfo(context, user.getNickname()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildSessionCookie("", 0));
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        UserContext context = requireCurrentUser();
        SysUser user = parseUserId(context.getUserId()) != null
                ? sysUserService.findById(parseUserId(context.getUserId()))
                : null;
        return ApiResponse.success(toUserInfo(context, user != null ? user.getNickname() : null));
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        UserContext context = requireCurrentUser();
        Long userId = parseUserId(context.getUserId());
        if (userId == null) {
            throw new UnauthorizedException("当前会话不支持修改密码");
        }
        sysUserService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success();
    }

    private UserContext requireCurrentUser() {
        UserContext context = UserContextHolder.getContext();
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

    private Map<String, Object> toUserInfo(UserContext context, String nickname) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", context.getUserId());
        info.put("username", context.getUsername());
        info.put("nickname", nickname != null ? nickname : context.getUsername());
        info.put("role", context.getRole());
        info.put("authSource", context.getAuthSource());
        return info;
    }

    private String buildSessionCookie(String value, long maxAgeSeconds) {
        OdwAuthProperties.Session session = properties.getSession();
        StringBuilder cookie = new StringBuilder()
                .append(session.getCookieName()).append('=').append(value)
                .append("; Path=/; HttpOnly; Max-Age=").append(maxAgeSeconds)
                .append("; SameSite=").append(session.getSameSite());
        if (session.isSecure()) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }
}
