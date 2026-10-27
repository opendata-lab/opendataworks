package com.onedata.portal.agentapi.service;

import com.onedata.auth.user.SysUser;
import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.DatasourceSubjectAccountService;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgentDatasourceAccessService {
    private final DatasourceSubjectAccountService accounts;
    private final DorisClusterMapper clusters;
    private final DatasourceAdapterRegistry adapters;

    public void applyCredentials(AgentDatasourceResolution datasource) {
        AgentIdentityContext.Identity identity = AgentIdentityContext.require();
        DatasourceSubjectAccount agent = accounts.require(datasource.getClusterId(), "AGENT", identity.getAgentId());
        datasource.setUser(agent.getUsername());
        datasource.setPassword(agent.getPassword());
        datasource.setAgentId(identity.getAgentId());
        SysUser user = accounts.resolveUser(identity.getUserId());
        DatasourceSubjectAccount person = accounts.require(datasource.getClusterId(), "USER", String.valueOf(user.getId()));
        datasource.setUserId(String.valueOf(user.getId()));
        datasource.setCheckUser(person.getUsername());
        datasource.setCheckPassword(person.getPassword());
    }

    public boolean isVisible(Long sourceId, String database, String table) {
        return visibleColumns(sourceId).containsKey(key(database, table));
    }

    public boolean isColumnVisible(Long sourceId, String database, String table, String column) {
        Set<String> columns = visibleColumns(sourceId).get(key(database, table));
        return columns != null && columns.contains(column);
    }

    private Map<String, Set<String>> visibleColumns(Long sourceId) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes == null ? null : attributes.getRequest();
        String cacheKey = getClass().getName() + "." + sourceId;
        if (request != null && request.getAttribute(cacheKey) != null) {
            @SuppressWarnings("unchecked")
            Map<String, Set<String>> cached = (Map<String, Set<String>>) request.getAttribute(cacheKey);
            return cached;
        }
        Map<String, Set<String>> result = loadVisibleColumns(sourceId);
        if (request != null) {
            // 只复用本次请求的实时枚举，下一次请求重新读取数据库权限。
            request.setAttribute(cacheKey, result);
        }
        return result;
    }

    private Map<String, Set<String>> loadVisibleColumns(Long sourceId) {
        AgentIdentityContext.Identity identity = AgentIdentityContext.require();
        DorisCluster cluster = clusters.selectById(sourceId);
        if (cluster == null) {
            return new LinkedHashMap<>();
        }
        DatasourceMetadataReader reader = adapters.require(cluster.getSourceType()).metadata();
        try (Connection agent = accounts.open(sourceId, "AGENT", identity.getAgentId(), null, true)) {
            Map<String, Set<String>> result = enumerate(reader, agent);
            SysUser user = accounts.resolveUser(identity.getUserId());
            try (Connection person = accounts.open(sourceId, "USER", String.valueOf(user.getId()), null, true)) {
                Map<String, Set<String>> visible = enumerate(reader, person);
                result.keySet().retainAll(visible.keySet());
                result.forEach((key, columns) -> columns.retainAll(visible.get(key)));
            }
            return result;
        } catch (SQLException | IllegalArgumentException e) {
            // 无映射/不可连接的源不出现在模型的目录中；实际查询仍返回明确拒绝原因。
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Set<String>> enumerate(DatasourceMetadataReader reader, Connection connection) {
        return reader.visibleColumns(connection);
    }

    private String key(String namespace, String table) {
        return namespace + "\u0000" + table;
    }
}
