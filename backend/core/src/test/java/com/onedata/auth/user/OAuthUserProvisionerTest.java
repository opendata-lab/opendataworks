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
    void createsNewUserWithExpectedDefaults() {
        when(sysUserMapper.findAllByUsername("sso:u-100")).thenReturn(null);
        when(sysUserMapper.insert(any(SysUser.class)))
                .thenAnswer(
                        call -> {
                            SysUser user = call.getArgument(0);
                            user.setId(42L);
                            return 1;
                        });

        SysUser user = provisioner.provision("sso", "u-100", "Alice", "alice@example.com");

        assertEquals(42L, user.getId());
        assertEquals("sso:u-100", user.getUsername());
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

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(captor.capture());
        assertEquals("sso:u-100", captor.getValue().getUsername());
        assertNull(captor.getValue().getPasswordHash());
    }

    @Test
    void refreshesNicknameEmailAndLastLoginWithoutChangingRole() {
        SysUser existing = activeUser(11L, "sso:u-100", "sso", "admin");
        existing.setNickname("Old Name");
        existing.setEmail("old@example.com");
        when(sysUserMapper.findAllByUsername("sso:u-100")).thenReturn(existing);
        when(sysUserMapper.updateOAuthLoginProfile(
                        eq(11L), eq("New Name"), eq("new@example.com"), any(), any()))
                .thenReturn(1);

        SysUser result = provisioner.provision("sso", "u-100", "New Name", "new@example.com");

        assertEquals("admin", result.getRole());
        assertEquals("New Name", result.getNickname());
        assertEquals("new@example.com", result.getEmail());
        assertNotNull(result.getLastLoginAt());
        verify(sysUserMapper, never()).insert(any());
        verify(sysUserMapper)
                .updateOAuthLoginProfile(
                        eq(11L), eq("New Name"), eq("new@example.com"), any(), any());
    }

    @Test
    void clearsEmailWhenMissingOrOverLimitAndFallsBackAndTruncatesNickname() {
        SysUser existing = activeUser(12L, "sso:sub-1", "sso", "user");
        existing.setEmail("stale@example.com");
        when(sysUserMapper.findAllByUsername("sso:sub-1")).thenReturn(existing);
        when(sysUserMapper.updateOAuthLoginProfile(eq(12L), eq("sub-1"), isNull(), any(), any()))
                .thenReturn(1);

        SysUser cleared = provisioner.provision("sso", "sub-1", "   ", "x".repeat(129));
        assertEquals("sub-1", cleared.getNickname());
        assertNull(cleared.getEmail());

        String longNickname = "n".repeat(150);
        when(sysUserMapper.updateOAuthLoginProfile(
                        eq(12L), eq("n".repeat(128)), isNull(), any(), any()))
                .thenReturn(1);
        SysUser truncated = provisioner.provision("sso", "sub-1", longNickname, "");
        assertEquals(128, truncated.getNickname().length());
        assertNull(truncated.getEmail());
    }

    @Test
    void rejectsDisabledOrSoftDeletedUser() {
        SysUser disabled = activeUser(13L, "sso:u-disabled", "sso", "user");
        disabled.setEnabled(false);
        when(sysUserMapper.findAllByUsername("sso:u-disabled")).thenReturn(disabled);

        OAuthProvisioningException disabledEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "u-disabled", "Bob", null));
        assertEquals(OAuthUserProvisioner.ERROR_ACCOUNT_DISABLED, disabledEx.getErrorCode());

        SysUser deleted = activeUser(14L, "sso:u-deleted", "sso", "user");
        deleted.setDeleted(1);
        when(sysUserMapper.findAllByUsername("sso:u-deleted")).thenReturn(deleted);

        OAuthProvisioningException deletedEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "u-deleted", "Carol", null));
        assertEquals(OAuthUserProvisioner.ERROR_ACCOUNT_DISABLED, deletedEx.getErrorCode());
    }

    @Test
    void rejectsAuthSourceMismatchAndCaseDifferentUsername() {
        SysUser localAccount = activeUser(15L, "sso:alice", "local", "user");
        when(sysUserMapper.findAllByUsername("sso:alice")).thenReturn(localAccount);

        OAuthProvisioningException mismatchEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "alice", "Alice", null));
        assertEquals(OAuthUserProvisioner.ERROR_OAUTH_FAILED, mismatchEx.getErrorCode());

        SysUser upperCase = activeUser(16L, "sso:Bob", "sso", "user");
        when(sysUserMapper.findAllByUsername("sso:bob")).thenReturn(upperCase);

        OAuthProvisioningException caseEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "bob", "Bob", null));
        assertEquals(OAuthUserProvisioner.ERROR_OAUTH_FAILED, caseEx.getErrorCode());
    }

    @Test
    void retriesLookupOnceOnConcurrentDuplicateKey() {
        SysUser winner = activeUser(20L, "sso:race", "sso", "user");
        when(sysUserMapper.findAllByUsername("sso:race")).thenReturn(null).thenReturn(winner);
        when(sysUserMapper.insert(any(SysUser.class)))
                .thenThrow(new DuplicateKeyException("uk_username"));
        when(sysUserMapper.updateOAuthLoginProfile(
                        eq(20L), eq("Race User"), eq("race@example.com"), any(), any()))
                .thenReturn(1);

        SysUser result = provisioner.provision("sso", "race", "Race User", "race@example.com");

        assertEquals(20L, result.getId());
        assertEquals("Race User", result.getNickname());
        verify(sysUserMapper, times(2)).findAllByUsername("sso:race");
        verify(sysUserMapper)
                .updateOAuthLoginProfile(
                        eq(20L), eq("Race User"), eq("race@example.com"), any(), any());
    }

    @Test
    void rejectsOverlongUsernameAndBlankSubject() {
        String longSub = "s".repeat(125); // "sso:" (4) + 125 = 129 > 128
        OAuthProvisioningException lengthEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", longSub, "Long", null));
        assertEquals(OAuthUserProvisioner.ERROR_OAUTH_FAILED, lengthEx.getErrorCode());

        OAuthProvisioningException blankSubEx =
                assertThrows(
                        OAuthProvisioningException.class,
                        () -> provisioner.provision("sso", "   ", "Blank", null));
        assertEquals(OAuthUserProvisioner.ERROR_OAUTH_FAILED, blankSubEx.getErrorCode());
        verify(sysUserMapper, never()).findAllByUsername(any());
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
