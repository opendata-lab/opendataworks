package com.onedata.portal.agentapi;

import com.onedata.auth.user.SysUser;
import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import com.onedata.portal.agentapi.service.AgentDatasourceAccessService;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.DatasourceSubjectAccountService;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDatasourceAccessServiceTest {
    private final DatasourceSubjectAccountService accounts = mock(DatasourceSubjectAccountService.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final DatasourceAdapterRegistry adapters = mock(DatasourceAdapterRegistry.class);
    private final DatasourceMetadataReader reader = mock(DatasourceMetadataReader.class);
    private final Connection agent = mock(Connection.class);
    private final Connection person = mock(Connection.class);
    private final AgentDatasourceAccessService service = new AgentDatasourceAccessService(accounts, clusters, adapters);

    @BeforeEach
    void setup() throws Exception {
        DorisCluster source = new DorisCluster();
        source.setId(1L);
        source.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(source);
        DatasourceAdapter adapter = mock(DatasourceAdapter.class);
        when(adapters.require("MYSQL")).thenReturn(adapter);
        when(adapter.metadata()).thenReturn(reader);
        when(accounts.open(1L, "AGENT", "a", null, true)).thenReturn(agent);
        when(accounts.open(1L, "USER", "2", null, true)).thenReturn(person);
        SysUser user = new SysUser();
        user.setId(2L);
        when(accounts.resolveUser("local:alice")).thenReturn(user);
        when(reader.visibleColumns(agent)).thenReturn(visible("both", "id", "both", "agent_secret", "agent_only", "id"));
        when(reader.visibleColumns(person)).thenReturn(visible("both", "id", "both", "person_secret", "person_only", "id"));
        AgentIdentityContext.set("local:alice", "a");
        newRequest();
    }

    @AfterEach
    void clear() {
        AgentIdentityContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void metadataUsesObjectAndColumnIntersectionAndRechecksNextRequest() {
        assertTrue(service.isVisible(1L, "db", "both"));
        assertFalse(service.isVisible(1L, "db", "agent_only"));
        assertFalse(service.isVisible(1L, "db", "person_only"));
        assertTrue(service.isColumnVisible(1L, "db", "both", "id"));
        assertFalse(service.isColumnVisible(1L, "db", "both", "agent_secret"));
        assertFalse(service.isColumnVisible(1L, "db", "both", "person_secret"));
        when(reader.visibleColumns(person)).thenReturn(new LinkedHashMap<>());
        newRequest();
        assertFalse(service.isVisible(1L, "db", "both"));
    }

    private void newRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    /** 参数按 (表, 字段) 成对给出；同一张表的多个字段按顺序重复表名。 */
    private Map<String, Set<String>> visible(String... tableAndColumn) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < tableAndColumn.length; i += 2) {
            result.computeIfAbsent("db\u0000" + tableAndColumn[i], key -> new LinkedHashSet<>()).add(tableAndColumn[i + 1]);
        }
        return result;
    }
}
