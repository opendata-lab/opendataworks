package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.auth.user.SysUserService;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Platform membership only; datasource roles are managed separately. */
@Service
@RequiredArgsConstructor
public class PlatformUserManagementService {
    private final SysUserMapper users;
    private final BCryptPasswordEncoder encoder;
    private final com.onedata.portal.mapper.PlatformUserOperationMapper operations;

    @Data
    public static class UserRequest {
        private String accountType;
        private String description;
        private String username;
        private String nickname;
        private String email;
        @ToString.Exclude private String password;
        private String role;
        private Boolean enabled;
    }

    public List<SysUser> list(String keyword, Boolean enabled) {
        return list(keyword, enabled, null);
    }

    public List<SysUser> list(String keyword, Boolean enabled, String accountType) {
        validateType(accountType);
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String text = keyword.trim();
            query.and(
                    q ->
                            q.like(SysUser::getUsername, text)
                                    .or()
                                    .like(SysUser::getNickname, text)
                                    .or()
                                    .like(SysUser::getEmail, text));
        }
        if (enabled != null) query.eq(SysUser::getEnabled, enabled);
        if (accountType != null) query.eq(SysUser::getAccountType, accountType);
        return users.selectList(query.orderByAsc(SysUser::getId));
    }

    @Transactional
    public SysUser create(UserRequest input) {
        validateType(input.getAccountType());
        boolean service = "SERVICE".equals(input.getAccountType());
        if (service) validateService(input, true);
        String name =
                service
                        ? "svc_" + UUID.randomUUID().toString().replace("-", "")
                        : input.getUsername() == null ? "" : input.getUsername().trim();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"))
            throw new IllegalArgumentException("用户名为3～64位字母、数字、下划线、点或短横线");
        if (!service) SysUserService.validatePassword(input.getPassword());
        validateProfile(input);
        // Include deleted rows: the unique login name must never be silently recycled.
        if (users.countAllByUsername(name) > 0) throw new IllegalArgumentException("用户名已存在");
        SysUser user = new SysUser();
        user.setUsername(name);
        user.setNickname(
                StringUtils.hasText(input.getNickname()) ? input.getNickname().trim() : name);
        user.setAccountType(service ? "SERVICE" : "USER");
        user.setDescription(input.getDescription());
        user.setEmail(service ? null : input.getEmail());
        user.setPasswordHash(service ? null : encoder.encode(input.getPassword()));
        user.setRole(input.getRole() == null ? "user" : input.getRole());
        user.setEnabled(input.getEnabled() == null || input.getEnabled());
        user.setAuthSource(service ? "service" : "local");
        user.setFailedAttempts(0);
        user.setSessionVersion(0);
        user.setDeleted(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        users.insert(user);
        audit(user.getId(), "CREATE", "role=" + user.getRole() + ",enabled=" + user.getEnabled());
        return users.selectById(user.getId());
    }

    @Transactional
    public SysUser update(Long id, UserRequest input) {
        lockAdministrators();
        SysUser user = require(id);
        validateProfile(input);
        if (input.getAccountType() != null
                && !input.getAccountType()
                        .equals(user.getAccountType() == null ? "USER" : user.getAccountType()))
            throw new IllegalArgumentException("账号类型创建后不能修改");
        if (user.isServiceAccount()) validateService(input, false);
        String role = input.getRole() == null ? user.getRole() : input.getRole();
        boolean enabled =
                input.getEnabled() == null
                        ? Boolean.TRUE.equals(user.getEnabled())
                        : input.getEnabled();
        protectAdministrator(user, role, enabled);
        SysUser change = new SysUser();
        change.setId(id);
        if (input.getNickname() != null) change.setNickname(input.getNickname().trim());
        if (input.getEmail() != null) change.setEmail(input.getEmail());
        if (input.getDescription() != null) change.setDescription(input.getDescription().trim());
        change.setRole(role);
        change.setEnabled(enabled);
        // Changing enablement must invalidate old cookies even after re-enabling.
        change.setUpdatedAt(LocalDateTime.now());
        users.updateById(change);
        if (!Objects.equals(enabled, user.getEnabled())) {
            users.incrementSessionVersion(id);
        }
        audit(id, "UPDATE", "role=" + role + ",enabled=" + enabled);
        return users.selectById(id);
    }

    @Transactional
    public void resetPassword(Long id, String password) {
        SysUser user = require(id);
        if (user.isServiceAccount()) throw new IllegalArgumentException("服务账号不能设置登录密码，请管理Token");
        if (!"local".equals(user.getAuthSource()))
            throw new IllegalArgumentException("第三方账号请在身份提供方修改密码");
        SysUserService.validatePassword(password);
        // SQL increments atomically, including concurrent password changes.
        users.updatePasswordAndInvalidateSession(id, encoder.encode(password));
        audit(id, "RESET_PASSWORD", "旧会话已失效");
    }

    @Transactional
    public void delete(Long id) {
        lockAdministrators();
        SysUser user = require(id);
        protectAdministrator(user, user.getRole(), false);
        users.softDeleteAndInvalidateSession(id);
        // Native role memberships are not changed here; the admin UI revokes datasource access per
        // source before deleting when the admin keeps that option checked.
        audit(id, "DELETE", "停用并软删除；数据源权限由删除前的逐源回收处理，未勾选时保持不变");
    }

    private SysUser require(Long id) {
        SysUser user = users.selectById(id);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        return user;
    }

    private void validateType(String type) {
        if (type != null && !"USER".equals(type) && !"SERVICE".equals(type))
            throw new IllegalArgumentException("账号类型只能为USER或SERVICE");
    }

    private void validateService(UserRequest input, boolean creating) {
        if (StringUtils.hasText(input.getPassword())
                || StringUtils.hasText(input.getEmail())
                || input.getRole() != null && !"user".equals(input.getRole()))
            throw new IllegalArgumentException("服务账号不能设置密码、邮箱或平台管理员权限");
        if ((creating || input.getNickname() != null) && !StringUtils.hasText(input.getNickname()))
            throw new IllegalArgumentException("请输入服务账号名称");
    }

    private void validateProfile(UserRequest input) {
        if (input.getDescription() != null && input.getDescription().length() > 500)
            throw new IllegalArgumentException("用途不能超过500字");
        if (input.getRole() != null
                && !"admin".equals(input.getRole())
                && !"user".equals(input.getRole()))
            throw new IllegalArgumentException("平台角色只能为admin或user");
        if (input.getNickname() != null && input.getNickname().trim().length() > 100)
            throw new IllegalArgumentException("姓名不能超过100字");
        if (StringUtils.hasText(input.getEmail())
                && (!input.getEmail().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                        || input.getEmail().length() > 128))
            throw new IllegalArgumentException("邮箱格式不正确或超过128字");
    }

    private void protectAdministrator(SysUser user, String newRole, boolean enabled) {
        if ((!enabled || !"admin".equals(newRole))
                && String.valueOf(user.getId()).equals(PrincipalContextHolder.getCurrentUserId()))
            throw new IllegalArgumentException("不能停用、删除或降级当前登录管理员");
        if ("admin".equals(user.getRole())
                && Boolean.TRUE.equals(user.getEnabled())
                && (!enabled || !"admin".equals(newRole))) {
            Long count = users.countActiveAdmins();
            if (count == null || count <= 1) throw new IllegalArgumentException("至少保留一个启用的管理员");
        }
    }

    private void lockAdministrators() {
        users.lockActiveAdminIds();
    }

    private void audit(Long id, String action, String detail) {
        var operation = new com.onedata.portal.entity.PlatformUserOperation();
        operation.setOperatorId(PrincipalContextHolder.getCurrentUserId());
        operation.setUserId(id);
        operation.setAction(action);
        operation.setDetail(detail);
        operations.insert(operation);
    }
}
