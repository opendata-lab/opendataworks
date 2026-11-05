package com.onedata.portal.service.permission;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DatasourceAccessPermissionMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.ServiceAccountTokenMapper;
import com.onedata.portal.service.PlatformUserManagementService;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

class SubjectAccessServiceTest {
    private final PlatformUserManagementService userService =
            mock(PlatformUserManagementService.class);
    private final DatasourcePermissionService permissionService =
            mock(DatasourcePermissionService.class);
    private final DatasourceAccessPermissionMapper permissions =
            mock(DatasourceAccessPermissionMapper.class);
    private final ServiceAccountTokenMapper tokens = mock(ServiceAccountTokenMapper.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final SubjectAccessService service =
            new SubjectAccessService(
                    userService, permissionService, permissions, tokens, clusters, users);

    private static SysUser user(long id, String type) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setAccountType(type);
        u.setEnabled(true);
        return u;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void listMergesTokenStatsAndPerSourceRoleNames() {
        LocalDateTime used = LocalDateTime.of(2026, 11, 5, 9, 0);
        when(userService.list(null, null, null))
                .thenReturn(List.of(user(7, "USER"), user(8, "SERVICE")));
        when(tokens.tokenStats())
                .thenReturn(List.of(row("USER_ID", 8L, "ACTIVE_COUNT", 2L, "LAST_USED_AT", used)));
        when(permissions.listAccessSummaryRows())
                .thenReturn(
                        List.of(
                                row(
                                        "subject_type",
                                        "USER",
                                        "subject_id",
                                        "7",
                                        "source_id",
                                        1L,
                                        "cluster_name",
                                        "A",
                                        "status",
                                        "ACTIVE",
                                        "role_name",
                                        "R1"),
                                row(
                                        "subject_type",
                                        "USER",
                                        "subject_id",
                                        "7",
                                        "source_id",
                                        1L,
                                        "cluster_name",
                                        "A",
                                        "status",
                                        "ACTIVE",
                                        "role_name",
                                        "R2"),
                                row(
                                        "subject_type",
                                        "SERVICE",
                                        "subject_id",
                                        "8",
                                        "source_id",
                                        2L,
                                        "cluster_name",
                                        "B",
                                        "status",
                                        "ERROR",
                                        "role_name",
                                        null)));

        List<SubjectAccessService.UserView> views = service.listUsers(null, null, null);

        assertEquals(0, views.get(0).getActiveTokenCount());
        assertEquals(List.of("R1", "R2"), views.get(0).getAccessSummary().get(0).get("roleNames"));
        assertEquals(2, views.get(1).getActiveTokenCount());
        assertEquals(used, views.get(1).getLastTokenUsedAt());
        assertEquals("ERROR", views.get(1).getAccessSummary().get(0).get("status"));
        assertEquals(List.of(), views.get(1).getAccessSummary().get(0).get("roleNames"));
    }

    @Test
    void userViewSerializesUserFieldsFlatWithoutSecrets() throws Exception {
        SysUser u = user(7, "USER");
        u.setPasswordHash("$2a$secret");
        u.setSessionVersion(3);
        String json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()
                        .writeValueAsString(
                                new SubjectAccessService.UserView(u, 0, null, List.of()));
        assertTrue(json.contains("\"username\":\"u7\""), json);
        assertTrue(json.contains("\"accessSummary\":[]"), json);
        assertFalse(
                json.contains("secret")
                        || json.contains("passwordHash")
                        || json.contains("sessionVersion"),
                json);
    }

    @Test
    void accessReturnsMemberAndOnlyALatestFailurePerPermissionSource() {
        when(users.selectById(8L)).thenReturn(user(8, "SERVICE"));
        DorisCluster mysql = new DorisCluster();
        mysql.setId(1L);
        mysql.setClusterName("A");
        mysql.setSourceType("MYSQL");
        DorisCluster other = new DorisCluster();
        other.setId(2L);
        other.setSourceType("HIVE");
        DorisCluster pg = new DorisCluster();
        pg.setId(3L);
        pg.setSourceType("POSTGRESQL");
        when(clusters.selectList(any())).thenReturn(List.of(mysql, other, pg));
        Map<String, Object> member =
                row("subjectType", "SERVICE", "subjectId", "8", "roleIds", List.of(10L));
        when(permissionService.overview(1L))
                .thenReturn(
                        row(
                                "engine",
                                "MYSQL",
                                "roles",
                                List.of(),
                                "members",
                                List.of(row("subjectType", "USER", "subjectId", "8"), member),
                                "management",
                                row("configured", true)));
        when(permissionService.overview(3L))
                .thenReturn(
                        row(
                                "engine",
                                "POSTGRESQL",
                                "roles",
                                List.of(),
                                "members",
                                List.of(),
                                "management",
                                row("configured", false)));
        // Source 1: latest op for this subject failed. Source 3: an older failure followed by
        // success.
        when(permissionService.operations(1L))
                .thenReturn(
                        List.of(
                                row("roleId", 10L, "state", "FAILED"),
                                row(
                                        "subjectType",
                                        "SERVICE",
                                        "subjectId",
                                        "8",
                                        "state",
                                        "PARTIAL")));
        when(permissionService.operations(3L))
                .thenReturn(
                        List.of(
                                row("subjectType", "SERVICE", "subjectId", "8", "state", "SUCCESS"),
                                row(
                                        "subjectType",
                                        "SERVICE",
                                        "subjectId",
                                        "8",
                                        "state",
                                        "FAILED")));

        Map<String, Object> access = service.access(8L);

        assertEquals("SERVICE", access.get("accountType"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) access.get("sources");
        assertEquals(List.of(1L, 3L), sources.stream().map(s -> s.get("sourceId")).toList());
        assertSame(member, sources.get(0).get("member"));
        assertEquals(
                "PARTIAL", ((Map<?, ?>) sources.get(0).get("lastFailedOperation")).get("state"));
        assertNull(sources.get(1).get("member"));
        assertNull(sources.get(1).get("lastFailedOperation"));
        verify(permissionService, never()).overview(2L);
    }

    @Test
    void accessRejectsUnknownUser() {
        assertThrows(IllegalArgumentException.class, () -> service.access(99L));
    }
}
