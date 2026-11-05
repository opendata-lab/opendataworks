package com.onedata.portal.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.onedata.auth.aspect.AuthorizationAspect;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.auth.exception.ForbiddenException;
import com.onedata.portal.dto.MetadataSyncConfigRequest;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.service.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.Collections;
import java.util.Map;

class MetadataCollectionAuthorizationTest {
    private final DorisClusterService clusters = mock(DorisClusterService.class);
    private final DorisConnectionService connections = mock(DorisConnectionService.class);
    private final DataTableService tables = mock(DataTableService.class);
    private final MetadataSyncHistoryService history = mock(MetadataSyncHistoryService.class);
    private final DataTableMetadataSyncService sync = mock(DataTableMetadataSyncService.class);

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new AuthorizationAspect());
        return factory.getProxy();
    }

    private DorisClusterController controller() {
        return proxy(
                new DorisClusterController(
                        clusters,
                        connections,
                        tables,
                        history,
                        mock(SchemaBackupService.class),
                        null));
    }

    @AfterEach
    void clearIdentity() {
        PrincipalContextHolder.clear();
    }

    @Test
    void ordinaryUserCannotMutateSourceCoordinatesOrTestManagementConnection() {
        PrincipalContextHolder.setContext(new CurrentPrincipal("17", "alice", "user", "local"));
        DorisClusterController controller = controller();
        assertThrows(ForbiddenException.class, () -> controller.create(new DorisCluster()));
        assertThrows(ForbiddenException.class, () -> controller.update(1L, new DorisCluster()));
        assertThrows(ForbiddenException.class, () -> controller.delete(1L));
        assertThrows(ForbiddenException.class, () -> controller.setDefault(1L));
        assertThrows(ForbiddenException.class, () -> controller.testConnection(1L));
        verifyNoInteractions(clusters, connections);
    }

    @Test
    void ordinaryUserCannotPreviewConfigureOrReadCollectionHistory() {
        PrincipalContextHolder.setContext(new CurrentPrincipal("17", "alice", "user", "local"));
        DorisClusterController controller = controller();
        assertThrows(ForbiddenException.class, () -> controller.metadataScope(1L, null));
        assertThrows(
                ForbiddenException.class,
                () -> controller.metadataSyncConfig(1L, new MetadataSyncConfigRequest()));
        assertThrows(
                ForbiddenException.class, () -> controller.listSyncHistory(1L, 1, 20, null, null));
        assertThrows(ForbiddenException.class, () -> controller.getSyncHistoryDetail(1L, 7L));
        // 检查权限切面确实执行，而不只检查注解存在。
        verifyNoInteractions(clusters, connections);
    }

    @Test
    void ordinaryUserCannotTriggerSyncOrAudit() {
        PrincipalContextHolder.setContext(new CurrentPrincipal("17", "alice", "user", "local"));
        DataTableController controller =
                proxy(
                        new DataTableController(
                                tables,
                                mock(DataTableQueryService.class),
                                sync,
                                mock(
                                        com.onedata.portal.service.freshness.TableFreshnessService
                                                .class)));
        assertThrows(
                ForbiddenException.class,
                () -> controller.syncTableMetadataByName("dw", "orders", 1L));
        assertThrows(ForbiddenException.class, () -> controller.auditAllMetadata(1L));
        verifyNoInteractions(sync);
    }

    @Test
    void adminScopeUsesSystemAccountAndDoesNotCallUserDirectory() {
        PrincipalContextHolder.setContext(new CurrentPrincipal("17", "alice", "admin", "local"));
        DorisCluster source = new DorisCluster();
        source.setId(1L);
        source.setUsername("collector");
        source.setStatus("active");
        when(clusters.getById(1L)).thenReturn(source);
        when(connections.getAllDatabasesAsSystem(1L))
                .thenReturn(Collections.singletonList("public"));
        Map<String, Object> scope = controller().metadataScope(1L, null).getData();
        assertEquals("collector", scope.get("accountUsername"));
        assertEquals(Collections.singletonList("public"), scope.get("databases"));

        verify(connections, never()).getSchemaObjects(anyLong(), any());
    }
}
