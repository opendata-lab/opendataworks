package com.onedata.portal.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisAuditAccessSyncPropertiesTest {

    @Test
    void usesDocumentedDefaults() {
        DorisAuditAccessSyncProperties properties = new DorisAuditAccessSyncProperties();

        assertTrue(properties.isEnabled());
        assertEquals(600_000L, properties.getFixedDelayMs());
        assertEquals(2, properties.getSafetyLagMinutes());
        assertEquals(10, properties.getOverlapMinutes());
        assertEquals(5_000, properties.getBatchSize());
        assertEquals(400, properties.getSummaryRetentionDays());
        assertEquals(7, properties.getProcessedEventRetentionDays());
    }
}
