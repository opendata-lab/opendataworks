package com.onedata.auth.user;

import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.exception.OAuthProvisioningException;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** OAuth 用户首次自动建号与每次登录资料刷新（昵称只在为空时填） */
@RequiredArgsConstructor
public class OAuthUserProvisioner {

    public static final String ERROR_OAUTH_FAILED = "oauth_failed";
    public static final String ERROR_ACCOUNT_DISABLED = "oauth_account_disabled";
    public static final int MAX_NICKNAME_LENGTH = 128;
    public static final int MAX_EMAIL_LENGTH = 128;

    private static final Pattern REGISTRATION_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$");
    private static final Set<String> RESERVED_REGISTRATION_IDS =
            Set.of(
                    CurrentPrincipal.AUTH_SOURCE_LOCAL,
                    "service",
                    CurrentPrincipal.AUTH_SOURCE_ANONYMOUS);

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._@+-]{0,127}$");

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

    /**
     * OAuth 登录建号或绑定（与 DataAgent 账号规则一致，OA-08）：
     *
     * <ol>
     *   <li>(registrationId, sub) 已绑定：返回绑定的账号；旧格式用户名 {@code <registrationId>:<sub>}
     *       在新用户名空闲时改为普通用户名。
     *   <li>未绑定：用户名取 preferred_username（缺省为 sub），已有同名账号即绑定该账号，否则新建账号。
     * </ol>
     */
    public SysUser provision(
            String registrationId,
            String sub,
            String preferredUsername,
            String nickname,
            String email) {
        if (!isValidRegistrationId(registrationId)) {
            throw new OAuthProvisioningException(
                    ERROR_OAUTH_FAILED, "Invalid OAuth registration id");
        }
        if (sub == null || sub.isBlank()) {
            throw new OAuthProvisioningException(ERROR_OAUTH_FAILED, "OAuth subject is blank");
        }
        String subject = sub.trim();
        String resolvedNickname = normalizeNickname(nickname, subject);
        String resolvedEmail = normalizeEmail(email);
        try {
            return bindOrCreate(
                    registrationId, subject, preferredUsername, resolvedNickname, resolvedEmail);
        } catch (DuplicateKeyException e) {
            // A concurrent first login won the username or identity insert; its binding now exists.
            try {
                return bindOrCreate(
                        registrationId,
                        subject,
                        preferredUsername,
                        resolvedNickname,
                        resolvedEmail);
            } catch (DuplicateKeyException again) {
                throw new OAuthProvisioningException(
                        ERROR_OAUTH_FAILED, "OAuth user creation conflicted", again);
            }
        }
    }

    private SysUser bindOrCreate(
            String registrationId,
            String subject,
            String preferredUsername,
            String nickname,
            String email) {
        LocalDateTime now = LocalDateTime.now();
        Long boundUserId = sysUserMapper.findUserIdByIdentity(registrationId, subject);
        if (boundUserId != null) {
            SysUser bound = sysUserMapper.findAllById(boundUserId);
            if (bound == null) {
                throw new OAuthProvisioningException(
                        ERROR_OAUTH_FAILED, "OAuth identity points to a missing user");
            }
            requireActive(bound);
            renameLegacyUsername(bound, registrationId, subject, preferredUsername);
            return refreshLogin(bound, nickname, email, now);
        }

        String username = resolveUsername(preferredUsername, subject);
        SysUser sameName = sysUserMapper.findAllByUsername(username);
        if (sameName != null) {
            if (sameName.isServiceAccount()) {
                throw new OAuthProvisioningException(
                        ERROR_OAUTH_FAILED, "OAuth username belongs to a service account");
            }
            requireActive(sameName);
            sysUserMapper.insertIdentity(registrationId, subject, sameName.getId());
            return refreshLogin(sameName, nickname, email, now);
        }

        SysUser created = new SysUser();
        created.setUsername(username);
        created.setAccountType("USER");
        created.setPasswordHash(null);
        created.setNickname(nickname);
        created.setEmail(email);
        created.setRole(CurrentPrincipal.ROLE_USER);
        created.setEnabled(true);
        created.setAuthSource(registrationId);
        created.setSessionVersion(0);
        created.setFailedAttempts(0);
        created.setLastLoginAt(now);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        created.setDeleted(0);
        sysUserMapper.insert(created);
        sysUserMapper.insertIdentity(registrationId, subject, created.getId());
        return created;
    }

    /** 用户名规则与 DataAgent 相同：1-128 位英文、数字或 . _ @ + -，以字母或数字开头 */
    public static boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    private static String resolveUsername(String preferredUsername, String subject) {
        String preferred = preferredUsername == null ? "" : preferredUsername.trim();
        String candidate = preferred.isEmpty() ? subject : preferred;
        if (!isValidUsername(candidate)) {
            throw new OAuthProvisioningException(
                    ERROR_OAUTH_FAILED,
                    "OAuth username must be 1-128 letters, digits or . _ @ + -");
        }
        return candidate;
    }

    private void renameLegacyUsername(
            SysUser user, String registrationId, String subject, String preferredUsername) {
        if (!(registrationId + ":" + subject).equals(user.getUsername())) {
            return;
        }
        String preferred = preferredUsername == null ? "" : preferredUsername.trim();
        String target = preferred.isEmpty() ? subject : preferred;
        if (!isValidUsername(target) || sysUserMapper.findAllByUsername(target) != null) {
            return;
        }
        if (sysUserMapper.renameUser(user.getId(), target) == 1) {
            user.setUsername(target);
        }
    }

    private static void requireActive(SysUser user) {
        if (Integer.valueOf(1).equals(user.getDeleted())
                || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new OAuthProvisioningException(ERROR_ACCOUNT_DISABLED, "账号已停用");
        }
    }

    private SysUser refreshLogin(SysUser user, String nickname, String email, LocalDateTime now) {
        requireActive(user);
        // Like DataAgent: the IdP name only fills an empty nickname, never overwrites one.
        String keptNickname =
                user.getNickname() == null || user.getNickname().isBlank()
                        ? nickname
                        : user.getNickname();
        int updated =
                sysUserMapper.updateOAuthLoginProfile(
                        user.getId(), keptNickname, email, now, now);
        if (updated != 1) {
            throw new OAuthProvisioningException(ERROR_ACCOUNT_DISABLED, "账号已停用");
        }
        user.setNickname(keptNickname);
        user.setEmail(email);
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        return user;
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
