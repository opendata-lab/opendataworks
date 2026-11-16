package com.onedata.auth.user;

import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.exception.OAuthProvisioningException;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** OAuth 用户首次自动建号与每次登录资料刷新 */
@RequiredArgsConstructor
public class OAuthUserProvisioner {

    public static final String ERROR_OAUTH_FAILED = "oauth_failed";
    public static final String ERROR_ACCOUNT_DISABLED = "oauth_account_disabled";
    public static final int MAX_USERNAME_LENGTH = 128;
    public static final int MAX_NICKNAME_LENGTH = 128;
    public static final int MAX_EMAIL_LENGTH = 128;

    private static final Pattern REGISTRATION_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$");
    private static final Set<String> RESERVED_REGISTRATION_IDS =
            Set.of(
                    CurrentPrincipal.AUTH_SOURCE_LOCAL,
                    "service",
                    CurrentPrincipal.AUTH_SOURCE_ANONYMOUS);

    private final SysUserMapper sysUserMapper;

    /** 校验 OAuth registration id 符合表列长度与保留字约束，非法时抛出 IllegalArgumentException */
    public static void validateRegistrationId(String registrationId) {
        if (!isValidRegistrationId(registrationId)) {
            throw new IllegalArgumentException(
                    "Invalid OAuth2 client registration id '"
                            + registrationId
                            + "': must be 1-32 chars starting with letter/digit using"
                            + " [A-Za-z0-9._-] and not local/service/anonymous");
        }
    }

    public static boolean isValidRegistrationId(String registrationId) {
        if (registrationId == null || !REGISTRATION_ID_PATTERN.matcher(registrationId).matches()) {
            return false;
        }
        return !RESERVED_REGISTRATION_IDS.contains(registrationId.toLowerCase(Locale.ROOT));
    }

    /** 按 registrationId + sub 查找或建号，刷新显示名/邮箱/最近登录时间 */
    public SysUser provision(String registrationId, String sub, String nickname, String email) {
        if (!isValidRegistrationId(registrationId)) {
            throw new OAuthProvisioningException(
                    ERROR_OAUTH_FAILED, "Invalid OAuth registration id");
        }
        if (sub == null || sub.isBlank()) {
            throw new OAuthProvisioningException(ERROR_OAUTH_FAILED, "OAuth subject is blank");
        }
        String username = registrationId + ":" + sub;
        if (username.length() > MAX_USERNAME_LENGTH) {
            throw new OAuthProvisioningException(
                    ERROR_OAUTH_FAILED, "OAuth username exceeds max length");
        }
        String resolvedNickname = normalizeNickname(nickname, sub);
        String resolvedEmail = normalizeEmail(email);
        LocalDateTime now = LocalDateTime.now();

        SysUser existing = sysUserMapper.findAllByUsername(username);
        if (existing != null) {
            return updateExistingUser(
                    existing, username, registrationId, resolvedNickname, resolvedEmail, now);
        }

        SysUser created = new SysUser();
        created.setUsername(username);
        created.setAccountType("USER");
        created.setPasswordHash(null);
        created.setNickname(resolvedNickname);
        created.setEmail(resolvedEmail);
        created.setRole(CurrentPrincipal.ROLE_USER);
        created.setEnabled(true);
        created.setAuthSource(registrationId);
        created.setSessionVersion(0);
        created.setFailedAttempts(0);
        created.setLastLoginAt(now);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        created.setDeleted(0);
        try {
            sysUserMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            SysUser concurrent = sysUserMapper.findAllByUsername(username);
            if (concurrent == null) {
                throw new OAuthProvisioningException(
                        ERROR_OAUTH_FAILED, "OAuth user creation conflicted without record", e);
            }
            return updateExistingUser(
                    concurrent, username, registrationId, resolvedNickname, resolvedEmail, now);
        }
    }

    private SysUser updateExistingUser(
            SysUser existing,
            String username,
            String registrationId,
            String nickname,
            String email,
            LocalDateTime now) {
        if (!Objects.equals(existing.getUsername(), username)
                || !Objects.equals(existing.getAuthSource(), registrationId)
                || existing.isServiceAccount()) {
            throw new OAuthProvisioningException(
                    ERROR_OAUTH_FAILED, "OAuth user identity mismatch");
        }
        if (Integer.valueOf(1).equals(existing.getDeleted())
                || !Boolean.TRUE.equals(existing.getEnabled())) {
            throw new OAuthProvisioningException(ERROR_ACCOUNT_DISABLED, "账号已停用");
        }
        int updated =
                sysUserMapper.updateOAuthLoginProfile(existing.getId(), nickname, email, now, now);
        if (updated != 1) {
            throw new OAuthProvisioningException(ERROR_ACCOUNT_DISABLED, "账号已停用");
        }
        existing.setNickname(nickname);
        existing.setEmail(email);
        existing.setLastLoginAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }

    private static String normalizeNickname(String nickname, String sub) {
        String candidate = nickname != null && !nickname.isBlank() ? nickname.trim() : sub;
        return truncate(candidate, MAX_NICKNAME_LENGTH);
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_EMAIL_LENGTH) {
            return null;
        }
        return trimmed;
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        int end =
                Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return value.substring(0, end);
    }
}
