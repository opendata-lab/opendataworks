package com.onedata.auth.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.auth.exception.AuthFailureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

/**
 * 本地用户登录与口令管理
 * bcrypt 校验 + 连续失败计数锁定
 */
@Slf4j
@RequiredArgsConstructor
public class SysUserService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final int LOCK_MINUTES = 15;
    static final int MIN_PASSWORD_LENGTH = 8;

    private static final String BAD_CREDENTIALS_MESSAGE = "用户名或密码错误";

    private final SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    public SysUser findByUsername(String username) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
    }

    public SysUser findById(Long id) {
        return sysUserMapper.selectById(id);
    }

    /**
     * 密码登录校验：成功返回用户并重置失败计数；失败抛出 AuthFailureException
     */
    public SysUser login(String username, String rawPassword) {
        SysUser user = findByUsername(username);
        if (user == null || user.getPasswordHash() == null) {
            log.info("Login failed, unknown or password-less user: {}", username);
            throw new AuthFailureException(BAD_CREDENTIALS_MESSAGE);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthFailureException("账号已停用");
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new AuthFailureException("登录失败次数过多，账号已锁定，请稍后重试");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            recordFailure(user, now);
            throw new AuthFailureException(BAD_CREDENTIALS_MESSAGE);
        }

        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setFailedAttempts(0);
        update.setLastLoginAt(now);
        sysUserMapper.updateById(update);
        user.setFailedAttempts(0);
        user.setLastLoginAt(now);
        return user;
    }

    /**
     * 登录后修改口令
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getPasswordHash() == null) {
            throw new AuthFailureException("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new AuthFailureException("原密码错误");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new AuthFailureException("新密码长度不能少于" + MIN_PASSWORD_LENGTH + "位");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(update);
        log.info("Password changed for user: {}", user.getUsername());
    }

    private void recordFailure(SysUser user, LocalDateTime now) {
        int attempts = (user.getFailedAttempts() == null ? 0 : user.getFailedAttempts()) + 1;
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setFailedAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            update.setLockedUntil(now.plusMinutes(LOCK_MINUTES));
            log.warn("User {} locked for {} minutes after {} failed attempts",
                    user.getUsername(), LOCK_MINUTES, attempts);
        }
        sysUserMapper.updateById(update);
    }
}
