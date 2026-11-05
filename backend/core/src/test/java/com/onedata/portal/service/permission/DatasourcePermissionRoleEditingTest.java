package com.onedata.portal.service.permission;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DatasourceAccessPermissionMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.permission.PermissionModels.*;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

class DatasourcePermissionRoleEditingTest {
    private final DatasourceAccessPermissionMapper permissions =
            mock(DatasourceAccessPermissionMapper.class);
    private DatasourcePermissionService service;

    @BeforeEach
    void setup() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection lock = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(lock);
        when(lock.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        DorisClusterMapper clusters = mock(DorisClusterMapper.class);
        when(clusters.selectById(1L)).thenReturn(new DorisCluster());
        when(permissions.findRole(2L, 1L))
                .thenReturn(List.of(Map.of("id", 2L, "name", "reader", "template", "READWRITE")));
        service =
                new DatasourcePermissionService(
                        permissions,
                        ds,
                        null,
                        clusters,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new ObjectMapper());
        PrincipalContextHolder.setContext(new CurrentPrincipal("1", "admin", "admin", "local"));
    }

    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    @Test
    void templateOnlyPatchPreservesNameAndAllowsReadonlyWhenSavedGrantsAreReadOnly() {
        when(permissions.listRoleGrants(2L))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "resourceType",
                                        "TABLE",
                                        "databaseName",
                                        "sales",
                                        "tableName",
                                        "orders",
                                        "actions",
                                        "[\"QUERY\"]")));
        RoleRequest request = new RoleRequest();
        request.setTemplate("READONLY");
        service.renameRole(1L, 2L, request);
        verify(permissions).updateRole("reader", "READONLY", 2L, 1L);
    }

    @Test
    void readonlyRollbackRejectsSavedWriteActionsWithoutWriting() {
        when(permissions.listRoleGrants(2L))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "resourceType",
                                        "TABLE",
                                        "databaseName",
                                        "sales",
                                        "tableName",
                                        "orders",
                                        "actions",
                                        "[\"QUERY\",\"WRITE\"]")));
        RoleRequest request = new RoleRequest();
        request.setTemplate("READONLY");
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> service.renameRole(1L, 2L, request))
                        .getMessage()
                        .contains("先移除"));
        verify(permissions, never()).updateRole(any(), any(), any(), any());
    }

    @Test
    void explicitUnlockAndNameOnlyPatchAndInvalidTemplate() {
        when(permissions.findRole(2L, 1L))
                .thenReturn(List.of(Map.of("id", 2L, "name", "reader", "template", "READONLY")));
        RoleRequest request = new RoleRequest();
        request.setName("writer");
        request.setTemplate("CUSTOM");
        service.renameRole(1L, 2L, request);
        verify(permissions).updateRole("writer", "CUSTOM", 2L, 1L);
        request.setTemplate(null);
        service.renameRole(1L, 2L, request);
        verify(permissions).updateRole("writer", "READONLY", 2L, 1L);
        request.setTemplate("INVALID");
        assertThrows(IllegalArgumentException.class, () -> service.renameRole(1L, 2L, request));
    }

    @Test
    void changesGroupDuplicateResourcesAndReportAddRemoveUpdateAcrossDatabases() {
        GrantSelection unchanged = grant("SCHEMA", "sales", "ignored", "QUERY");
        List<Map<String, Object>> changes =
                PermissionGrantChanges.between(
                        List.of(
                                unchanged,
                                grant("TABLE", "sales", "orders", "QUERY"),
                                grant("VIEW", "archive", "old", "QUERY")),
                        List.of(
                                grant("SCHEMA", "sales", "", "QUERY"),
                                grant("TABLE", "sales", "orders", "QUERY"),
                                grant("TABLE", "sales", "orders", "WRITE", "WRITE"),
                                grant("TABLE", "analytics", "new", "QUERY")));
        assertEquals(3, changes.size());
        assertEquals("UPDATE", changes.get(0).get("changeType"));
        assertEquals(List.of("WRITE"), changes.get(0).get("addedActions"));
        assertEquals(List.of("QUERY"), changes.get(0).get("beforeActions"));
        assertEquals("REMOVE", changes.get(1).get("changeType"));
        assertEquals(List.of("QUERY"), changes.get(1).get("removedActions"));
        assertEquals("ADD", changes.get(2).get("changeType"));
        assertTrue(
                PermissionGrantChanges.between(List.of(unchanged), List.of(unchanged)).isEmpty());
    }

    private GrantSelection grant(String type, String db, String table, String... actions) {
        GrantSelection grant = new GrantSelection();
        grant.setResourceType(type);
        grant.setDatabase(db);
        grant.setTableName(table);
        grant.setActions(Arrays.asList(actions));
        return grant;
    }
}
