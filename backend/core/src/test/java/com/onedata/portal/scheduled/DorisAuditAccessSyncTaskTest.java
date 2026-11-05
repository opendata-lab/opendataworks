package com.onedata.portal.scheduled;

import com.onedata.portal.config.DorisAuditAccessSyncProperties;
import com.onedata.portal.service.audit.DorisAuditAccessSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DorisAuditAccessSyncTaskTest {

    private final DorisAuditAccessSyncService syncService = mock(DorisAuditAccessSyncService.class);
    private final DorisAuditAccessSyncProperties properties = new DorisAuditAccessSyncProperties();
    private final DorisAuditAccessSyncTask task = new DorisAuditAccessSyncTask(properties, syncService);

    @Test
    void disabledPropertySkipsAuditSourceReads() {
        properties.setEnabled(false);

        task.syncAuditAccess();

        verify(syncService, never()).syncActiveClusters();
    }

    @Test
    void syncRunsByDefault() {
        task.syncAuditAccess();

        verify(syncService).syncActiveClusters();
    }

    @Test
    void cleanupStillRunsWhenSyncIsDisabled() {
        properties.setEnabled(false);

        task.cleanupAuditAccess();

        verify(syncService).cleanupExpiredData();
    }

    @Test
    void syncFailureDoesNotEscapeToScheduler() {
        Mockito.doThrow(new IllegalStateException("cluster lookup failed"))
                .when(syncService).syncActiveClusters();

        task.syncAuditAccess();

        verify(syncService).syncActiveClusters();
    }
}
