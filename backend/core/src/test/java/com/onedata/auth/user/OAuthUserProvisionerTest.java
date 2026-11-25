package com.onedata.auth.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onedata.auth.exception.OAuthProvisioningException;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
class OAuthUserProvisionerTest {

    @Mock private SysUserMapper sysUserMapper;

    private OAuthUserProvisioner provisioner;

    @BeforeEach
    void setUp() {
        provisioner = new OAuthUserProvisioner(sysUserMapper);
    }

    @Test
    void createsNewUserNamedByPreferredUsernameAndBindsIdentity() {
        when(sysUserMapper.findUserIdByIdentity("sso", "u-100")).thenReturn(null);
        when(sysUserMapper.findAllByUsername("alice")).thenReturn(null);
        when(sysUserMapper.insert(any(SysUser.class)))
                .thenAnswer(
                        call -> {
                            SysUser user = call.getArgument(0);
                            user.setId(42L);
                            return 1;
                        });

        SysUser user =
                provisioner.provision("sso", "u-100", "alice", "Alice", "alice@example.com");

        assertEquals(42L, user.getId());
        assertEquals("alice", user.getUsername());
        assertEquals("sso", user.getAuthSource());
        assertEquals("USER", user.getAccountType());
        assertEquals("user", user.getRole());
        assertNull(user.getPasswordHash());
        assertEquals("Alice", user.getNickname());
        assertEquals("alice@example.com", user.getEmail());
        assertTrue(Boolean.TRUE.equals(user.getEnabled()));
        assertEquals(0, user.getDeleted());
        assertEquals(0, user.getSessionVersion());
        assertNotNull(user.getLastLoginAt());
        verify(sysUserMapper).insertIdentity("sso", "u-100", 42L);
    }

    @Test
    void fallsBackToSubjectWhenPreferredUsernameIsMissing() {
        when(sysUserMapper.findUserIdByIdentity("sso", "u-100")).thenReturn(null);
        when(sysUserMapper.findAllByUsername("u-100")).thenReturn(null);
        when(sysUserMapper.insert(any(SysUser.class))).thenReturn(1);

        SysUser user = provisioner.provision("sso", "u-100", "  ", "Alice", null);

        assertEquals("u-100", user.getUsername());
    }

    @Test
    void boundIdentityKeepsNicknameAndRefreshesEmailWithoutChangingRoleOrName() {
        SysUser existing = activeUser(11L, "alice", "sso", "admin");
        existing.setNickname("Old Name");
        when(sysUserMapper.findUserIdByIdentity("sso", "u-100")).thenReturn(11L);
        when(sysUserMapper.findAllById(11L)).thenReturn(existing);
        when(sysUserMapper.updateOAuthLoginProfile(
                        eq(11L), eq("Old Name"), eq("new@example.com"), any(), any()))
                .thenReturn(1);

        SysUser result =
                provisioner.provision("sso", "u-100", "renamed", "New Name", "new@example.com");

        assertEquals("admin", result.getRole());
        assertEquals("alice", result.getUsername());
        assertEquals("Old Name", result.getNickname());
        assertEquals("new@example.com", result.getEmail());
        verify(sysUserMapper, never()).insert(any());
        verify(sysUserMapper, never()).renameUser(any(), any());
        verify(sysUserMapper, never()).insertIdentity(any(), any(), any());
    }

    @Test
    void legacyPrefixedUsernameIsRenamedWhenTheNewNameIsFree() {
        SysUser legacy = activeUser(12L, "sso:u-100", "sso", "user");
        when(sysUserMapper.findUserIdByIdentity("sso", "u-100")).thenReturn(12L);
        when(sysUserMapper.findAllById(12L)).thenReturn(legacy);
        when(sysUserMapper.findAllByUsername("alice")).thenReturn(null);
        when(sysUserMapper.renameUser(12L, "alice")).thenReturn(1);
        when(sysUserMapper.updateOAuthLoginProfile(eq(12L), any(), any(), any(), any()))
                .thenReturn(1);

        SysUser result = provisioner.provision("sso", "u-100", "alice", "Alice", null);

        assertEquals("alice", result.getUsername());
        verify(sysUserMapper).renameUser(12L, "alice");
    }

    @Test
    void legacyPrefixedUsernameIsKeptWhenTheNewNameIsTaken() {
        SysUser legacy = activeUser(12L, "sso:u-100", "sso", "user");
        when(sysUserMapper.findUserIdByIdentity("sso", "u-100")).thenReturn(12L);
        when(sysUserMapper.findAllById(12L)).thenReturn(legacy);
        when(sysUserMapper.findAllByUsername("alice"))
                .thenReturn(activeUser(3L, "alice", "local", "user"));
        when(sysUserMapper.updateOAuthLoginProfile(eq(12L), any(), any(), any(), any()))
                .thenReturn(1);

        SysUser result = provisioner.provision("sso", "u-100", "alice", "Alice", null);

        assertEquals("sso:u-100", result.getUsername());
        verify(sysUserMapper, never()).renameUser(any(), any());
    }

    @Test
    void unboundIdentityBindsTheSameNamedAccount() {
        SysUser local = activeUser(5L, "alice", "local", "admin");
        local.setNickname("爱丽丝");
        when(sysUserMapper.findUserIdByIdentity("sso", "u-100")).thenReturn(null);
        when(sysUserMapper.findAllByUsername("alice")).thenReturn(local);
        when(sysUserMapper.updateOAuthLoginProfile(eq(5L), eq("爱丽丝"), any(), any(), any()))
                .thenReturn(1);

        SysUser result = provisioner.provision("sso", "u-100", "alice", "Alice", null);

        assertEquals(5L, result.getId());
        assertEquals("爱丽丝", result.getNickname());
        assertEquals("local", result.getAuthSource());
        verify(sysUserMapper).insertIdentity("sso", "u-100", 5L);
        verify(sysUserMapper, never()).insert(any());
    }

    @Test
    void neverBindsAServiceAccount() {
        SysUser service = activeUser(6L, "etl-bot", "local", "user");
        service.setAccountType("SERVICE");
        when(sysUserMapper.findUserIdByIdentity("sso", "u-100")).thenReturn(null);
        when(sysUserMapper.findAllByUsername("etl-bot")).thenReturn(service);

        OAuthProvisioningException ex =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "u-100", "etl-bot", "Bot", null));
        assertEquals(OAuthUserProvisioner.ERROR_OAUTH_FAILED, ex.getErrorCode());
        verify(sysUserMapper, never()).insertIdentity(any(), any(), any());
    }

    @Test
    void rejectsDisabledOrSoftDeletedAccounts() {
        SysUser disabled = activeUser(13L, "bob", "sso", "user");
        disabled.setEnabled(false);
        when(sysUserMapper.findUserIdByIdentity("sso", "u-disabled")).thenReturn(13L);
        when(sysUserMapper.findAllById(13L)).thenReturn(disabled);
        OAuthProvisioningException disabledEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "u-disabled", "bob", "Bob", null));
        assertEquals(OAuthUserProvisioner.ERROR_ACCOUNT_DISABLED, disabledEx.getErrorCode());

        SysUser disabledLegacy = activeUser(16L, "sso:u-old", "sso", "user");
        disabledLegacy.setEnabled(false);
        when(sysUserMapper.findUserIdByIdentity("sso", "u-old")).thenReturn(16L);
        when(sysUserMapper.findAllById(16L)).thenReturn(disabledLegacy);
        OAuthProvisioningException legacyEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "u-old", "dave", "Dave", null));
        assertEquals(OAuthUserProvisioner.ERROR_ACCOUNT_DISABLED, legacyEx.getErrorCode());
        verify(sysUserMapper, never()).renameUser(any(), any());

        SysUser deleted = activeUser(14L, "carol", "local", "user");
        deleted.setDeleted(1);
        when(sysUserMapper.findUserIdByIdentity("sso", "u-deleted")).thenReturn(null);
        when(sysUserMapper.findAllByUsername("carol")).thenReturn(deleted);
        OAuthProvisioningException deletedEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "u-deleted", "carol", "Carol", null));
        assertEquals(OAuthUserProvisioner.ERROR_ACCOUNT_DISABLED, deletedEx.getErrorCode());
        verify(sysUserMapper, never()).insertIdentity(any(), any(), any());
    }

    @Test
    void clearsOverlongEmailAndFallsBackAndTruncatesNickname() {
        SysUser existing = activeUser(15L, "sub-1", "sso", "user");
        when(sysUserMapper.findUserIdByIdentity("sso", "sub-1")).thenReturn(15L);
        when(sysUserMapper.findAllById(15L)).thenReturn(existing);
        when(sysUserMapper.updateOAuthLoginProfile(eq(15L), eq("sub-1"), isNull(), any(), any()))
                .thenReturn(1);
        SysUser cleared = provisioner.provision("sso", "sub-1", null, "   ", "x".repeat(129));
        assertEquals("sub-1", cleared.getNickname());
        assertNull(cleared.getEmail());

        when(sysUserMapper.updateOAuthLoginProfile(
                        eq(15L), eq("n".repeat(128)), isNull(), any(), any()))
                .thenReturn(1);
        existing.setNickname(null);
        SysUser truncated = provisioner.provision("sso", "sub-1", null, "n".repeat(150), "");
        assertEquals(128, truncated.getNickname().length());
    }

    @Test
    void retriesOnceOnConcurrentFirstLogin() {
        SysUser winner = activeUser(20L, "race", "sso", "user");
        when(sysUserMapper.findUserIdByIdentity("sso", "race")).thenReturn(null).thenReturn(20L);
        when(sysUserMapper.findAllByUsername("race")).thenReturn(null);
        when(sysUserMapper.insert(any(SysUser.class)))
                .thenThrow(new DuplicateKeyException("uk_username"));
        when(sysUserMapper.findAllById(20L)).thenReturn(winner);
        when(sysUserMapper.updateOAuthLoginProfile(eq(20L), any(), any(), any(), any()))
                .thenReturn(1);

        SysUser result = provisioner.provision("sso", "race", "race", "Race User", null);

        assertEquals(20L, result.getId());
        verify(sysUserMapper, times(2)).findUserIdByIdentity("sso", "race");
    }

    @Test
    void rejectsInvalidUsernameAndBlankSubject() {
        when(sysUserMapper.findUserIdByIdentity(any(), any())).thenReturn(null);
        for (String bad : new String[] {"sso:alice", "张三", "-alice", "a".repeat(129)}) {
            OAuthProvisioningException ex =
                    assertThrows(
                            OAuthProvisioningException.class,
                            () -> provisioner.provision("sso", "u-1", bad, "X", null),
                            "Expected invalid username: " + bad);
            assertEquals(OAuthUserProvisioner.ERROR_OAUTH_FAILED, ex.getErrorCode());
        }
        assertTrue(OAuthUserProvisioner.isValidUsername("alice.w+test@corp-1_x"));

        OAuthProvisioningException blankSubEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "   ", "alice", "Blank", null));
        assertEquals(OAuthUserProvisioner.ERROR_OAUTH_FAILED, blankSubEx.getErrorCode());
        verify(sysUserMapper, never()).insert(any());
    }

    @Test
    void registrationIdValidationEnforcesLengthCharsAndReservedNames() {
        OAuthUserProvisioner.validateRegistrationId("sso");
        OAuthUserProvisioner.validateRegistrationId("a");
        OAuthUserProvisioner.validateRegistrationId("corp.idp_1-prod");
        OAuthUserProvisioner.validateRegistrationId("a".repeat(32));

        for (String invalid :
                new String[] {
                    null,
                    "",
                    ".sso",
                    "-sso",
                    "_sso",
                    "sso:1",
                    "sso/1",
                    "a".repeat(33),
                    "local",
                    "LOCAL",
                    "service",
                    "Service",
                    "anonymous",
                    "ANONYMOUS"
                }) {
            assertFalse(
                    OAuthUserProvisioner.isValidRegistrationId(invalid),
                    "Expected invalid: " + invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OAuthUserProvisioner.validateRegistrationId(invalid));
        }
    }

    @Test
    void mapperSqlIncludesDeletedRowsAndExplicitlyClearsEmailInDuckDb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        String findSql =
                SysUserMapper.class
                        .getMethod("findAllByUsername", String.class)
                        .getAnnotation(Select.class)
                        .value()[0]
                        .replace("#{username}", "?");
        String updateSql =
                SysUserMapper.class
                        .getMethod(
                                "updateOAuthLoginProfile",
                                Long.class,
                                String.class,
                                String.class,
                                LocalDateTime.class,
                                LocalDateTime.class)
                        .getAnnotation(Update.class)
                        .value()[0]
                        .replace("#{nickname}", "?")
                        .replace("#{email}", "?")
                        .replace("#{lastLoginAt}", "?")
                        .replace("#{updatedAt}", "?")
                        .replace("#{id}", "?");

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE sys_user (id BIGINT, username VARCHAR, nickname VARCHAR, email"
                        + " VARCHAR, auth_source VARCHAR, enabled INTEGER, deleted INTEGER,"
                        + " last_login_at TIMESTAMP, updated_at TIMESTAMP)");
            st.execute(
                    "INSERT INTO sys_user VALUES (1, 'sso:deleted', 'Old', 'a@b.c', 'sso', 0, 1,"
                            + " NULL, NULL)");
            st.execute(
                    "INSERT INTO sys_user VALUES (2, 'sso:active', 'Old', 'a@b.c', 'sso', 1, 0,"
                            + " NULL, NULL)");

            try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                ps.setString(1, "sso:deleted");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1L, rs.getLong("id"));
                    assertEquals(1, rs.getInt("deleted"));
                }
            }

            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, "Updated");
                ps.setObject(2, null);
                ps.setTimestamp(3, now);
                ps.setTimestamp(4, now);
                ps.setLong(5, 2L);
                assertEquals(1, ps.executeUpdate());
            }

            try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                ps.setString(1, "sso:active");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Updated", rs.getString("nickname"));
                    assertNull(rs.getString("email"));
                    assertNotNull(rs.getTimestamp("last_login_at"));
                }
            }
        }
    }

    @Test
    void identitySqlAndV61BackfillWorkInDuckDb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        String findSql =
                SysUserMapper.class
                        .getMethod("findUserIdByIdentity", String.class, String.class)
                        .getAnnotation(Select.class)
                        .value()[0]
                        .replace("#{registrationId}", "?")
                        .replace("#{subject}", "?");
        String migration =
                new String(
                        java.nio.file.Files.readAllBytes(
                                java.nio.file.Path.of(
                                        "../app/src/main/resources/db/migration/V61__sys_user_identity.sql")),
                        java.nio.charset.StandardCharsets.UTF_8);
        String backfill =
                migration.substring(migration.indexOf("INSERT INTO")).replace("`", "").trim();
        if (backfill.endsWith(";")) {
            backfill = backfill.substring(0, backfill.length() - 1);
        }

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE sys_user (id BIGINT, username VARCHAR, auth_source VARCHAR)");
            st.execute(
                    "CREATE TABLE sys_user_identity (id BIGINT DEFAULT 0, registration_id"
                            + " VARCHAR, subject VARCHAR, user_id BIGINT)");
            st.execute(
                    "INSERT INTO sys_user VALUES (1, 'admin', 'local'), (2, 'sso:u-1:x', 'sso'),"
                            + " (3, 'corp:abc', 'corp'), (4, 'etl', 'service')");
            st.execute(backfill);

            try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                ps.setString(1, "sso");
                ps.setString(2, "u-1:x");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2L, rs.getLong(1));
                }
                ps.setString(1, "corp");
                ps.setString(2, "abc");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(3L, rs.getLong(1));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sys_user_identity")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    private static SysUser activeUser(Long id, String username, String authSource, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setAuthSource(authSource);
        user.setRole(role);
        user.setAccountType("USER");
        user.setEnabled(true);
        user.setDeleted(0);
        user.setSessionVersion(0);
        return user;
    }
}
