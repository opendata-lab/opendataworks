package com.onedata.portal.service;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.entity.SchemaBackupConfig;
import com.onedata.portal.mapper.SchemaBackupConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class SchemaBackupServiceTest {

    @Mock
    private SchemaBackupConfigMapper schemaBackupConfigMapper;
    @Mock
    private DorisClusterService dorisClusterService;
    @Mock
    private DorisConnectionService dorisConnectionService;
    @Mock
    private MinioConfigService minioConfigService;

    private SchemaBackupService schemaBackupService;

    @BeforeEach
    void setUp() {
        schemaBackupService = new SchemaBackupService(
                DatasourceAdapterFixtures.registry(new DorisJdbcProperties()),
                schemaBackupConfigMapper,
                dorisClusterService,
                dorisConnectionService,
                minioConfigService);
    }

    @Test
    void shouldRunNowWhenDueAndNotExecutedToday() {
        SchemaBackupConfig config = new SchemaBackupConfig();
        config.setBackupEnabled(1);
        config.setBackupTime("02:00");
        config.setLastBackupTime(LocalDateTime.of(2026, 2, 23, 2, 0));

        boolean due = schemaBackupService.shouldRunNow(config, LocalDateTime.of(2026, 2, 24, 2, 1));

        assertTrue(due);
    }

    @Test
    void shouldNotRunBeforeConfiguredTime() {
        SchemaBackupConfig config = new SchemaBackupConfig();
        config.setBackupEnabled(1);
        config.setBackupTime("10:30");
        config.setLastBackupTime(LocalDateTime.of(2026, 2, 23, 10, 31));

        boolean due = schemaBackupService.shouldRunNow(config, LocalDateTime.of(2026, 2, 24, 10, 29));

        assertFalse(due);
    }

    @Test
    void shouldNotRunWhenAlreadyExecutedToday() {
        SchemaBackupConfig config = new SchemaBackupConfig();
        config.setBackupEnabled(1);
        config.setBackupTime("01:00");
        config.setLastBackupTime(LocalDateTime.of(2026, 2, 24, 1, 5));

        boolean due = schemaBackupService.shouldRunNow(config, LocalDateTime.of(2026, 2, 24, 3, 0));

        assertFalse(due);
    }

    @Test
    void shouldNotRunForInvalidTimeFormat() {
        SchemaBackupConfig config = new SchemaBackupConfig();
        config.setBackupEnabled(1);
        config.setBackupTime("25:10");

        boolean due = schemaBackupService.shouldRunNow(config, LocalDateTime.of(2026, 2, 24, 3, 0));

        assertFalse(due);
    }
}
