package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link DolphinSchedulerService#buildDataxParams} emits a DolphinScheduler-native
 * DataX taskParams shape (wizard vs custom mode) and translates the column mapping correctly.
 */
class DolphinSchedulerDataxParamsTest {

    private final DolphinSchedulerService service = new DolphinSchedulerService(
            Mockito.mock(DolphinConfigService.class),
            new ObjectMapper(),
            Mockito.mock(DolphinOpenApiClient.class));

    @Test
    void wizardModeWithEmptyMappingSelectsAllColumns() {
        Map<String, Object> params = service.buildDataxParams(
                11L, "mysql", 22L, "doris", "src_table", "tgt_table", null);

        assertEquals(0, params.get("customConfig"));
        assertEquals("MYSQL", params.get("dsType"));
        assertEquals(11L, params.get("dataSource"));
        assertEquals("DORIS", params.get("dtType"));
        assertEquals(22L, params.get("dataTarget"));
        assertEquals("SELECT * FROM src_table", params.get("sql"));
        assertEquals("tgt_table", params.get("targetTable"));
        assertFalse(params.containsKey("json"));
        // No shell/SQL field leakage into the DataX node.
        assertFalse(params.containsKey("rawScript"));
        assertFalse(params.containsKey("displayRows"));
    }

    @Test
    void wizardModeWithCommaSeparatedColumns() {
        Map<String, Object> params = service.buildDataxParams(
                1L, "MYSQL", 2L, "MYSQL", "src", "tgt", "id, name , age");

        assertEquals("SELECT id, name, age FROM src", params.get("sql"));
    }

    @Test
    void wizardModeWithJsonArrayColumns() {
        Map<String, Object> params = service.buildDataxParams(
                1L, "MYSQL", 2L, "MYSQL", "src", "tgt", "[\"id\",\"name\"]");

        assertEquals("SELECT id, name FROM src", params.get("sql"));
    }

    @Test
    void wizardModeWithObjectColumnMappingUsesAlias() {
        Map<String, Object> params = service.buildDataxParams(
                1L, "MYSQL", 2L, "MYSQL", "src", "tgt", "{\"src_id\":\"dst_id\",\"name\":\"name\"}");

        assertEquals("SELECT src_id AS dst_id, name FROM src", params.get("sql"));
    }

    @Test
    void customModeWithFullDataxJobJson() {
        String job = "{\"job\":{\"content\":[]}}";
        Map<String, Object> params = service.buildDataxParams(
                1L, "MYSQL", 2L, "MYSQL", "src", "tgt", job);

        assertEquals(1, params.get("customConfig"));
        assertEquals(job, params.get("json"));
        assertFalse(params.containsKey("sql"));
        assertFalse(params.containsKey("dataSource"));
    }

    @Test
    void malformedJsonMappingThrows() {
        assertThrows(IllegalStateException.class, () -> service.buildDataxParams(
                1L, "MYSQL", 2L, "MYSQL", "src", "tgt", "{not json"));
    }
}
