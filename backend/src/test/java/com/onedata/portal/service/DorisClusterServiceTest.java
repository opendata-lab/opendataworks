package com.onedata.portal.service;

import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DorisClusterServiceTest {
    private DorisClusterMapper mapper;
    private DorisClusterService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DorisClusterMapper.class);
        service = new DorisClusterService(mapper, DatasourceAdapterFixtures.registry(new com.onedata.portal.config.DorisJdbcProperties()));
    }

    private DorisCluster datasource() {
        DorisCluster cluster = new DorisCluster();
        cluster.setClusterName("pgsql");
        cluster.setSourceType(" postgresql ");
        cluster.setDatabaseName(" business ");
        cluster.setFeHost("127.0.0.1");
        cluster.setFePort(5432);
        cluster.setUsername("reader");
        return cluster;
    }

    @Test
    void createNormalizesPostgresqlAndDatabase() {
        DorisCluster cluster = service.create(datasource());
        assertEquals("POSTGRESQL", cluster.getSourceType());
        assertEquals("business", cluster.getDatabaseName());
        verify(mapper).insert(cluster);
    }

    @Test
    void missingPostgresqlDatabaseCannotBeSaved() {
        DorisCluster cluster = datasource();
        cluster.setDatabaseName("  ");
        assertThrows(IllegalArgumentException.class, () -> service.create(cluster));
        verifyNoInteractions(mapper);
    }

    @Test
    void updatePreservesDatabaseAndPasswordWhenOmitted() {
        DorisCluster existing = datasource();
        existing.setDatabaseName("business");
        existing.setPassword("secret");
        when(mapper.selectById(3L)).thenReturn(existing);
        DorisCluster update = datasource();
        update.setDatabaseName(null);
        update.setPassword("");
        service.update(3L, update);
        assertEquals("business", update.getDatabaseName());
        assertEquals("secret", update.getPassword());
        verify(mapper).updateById(update);
    }

    @Test
    void mysqlAndDorisDoNotRequireConnectionDatabase() {
        for (String type : new String[] { "MYSQL", "DORIS" }) {
            DorisCluster cluster = datasource();
            cluster.setSourceType(type);
            cluster.setDatabaseName(null);
            assertDoesNotThrow(() -> service.create(cluster));
        }
    }

    @Test
    void unsupportedTypeCannotBeSaved() {
        DorisCluster cluster = datasource();
        cluster.setSourceType("UNKNOWN");
        assertThrows(RuntimeException.class, () -> service.create(cluster));
        verifyNoInteractions(mapper);
    }
}
