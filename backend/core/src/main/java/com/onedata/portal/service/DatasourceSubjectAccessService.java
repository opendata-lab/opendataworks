package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.onedata.auth.context.*;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.dto.DatasourceResolutionDto;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DatasourceSubjectAccountMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DatasourceSubjectAccessService {
    private final com.onedata.portal.mapper.DataTableMapper dataTableMapper;
    private final DatasourceSubjectAccountMapper accountMapper;
    private final DorisClusterMapper clusterMapper;
    private final SysUserMapper userMapper;
    private final DatasourceConnectionFactory connectionFactory;
    private final com.onedata.portal.mapper.DatasourceAccessPermissionMapper permissions;
    private final com.onedata.portal.service.permission.PermissionCredentialCipher credentialCipher;

    public DatasourceSubjectAccount require(Long sourceId, String type, String subjectId) {
        validateSubject(type, subjectId);
        DatasourceSubjectAccount account =
                accountMapper.selectOne(
                        new LambdaQueryWrapper<DatasourceSubjectAccount>()
                                .eq(DatasourceSubjectAccount::getSourceId, sourceId)
                                .eq(DatasourceSubjectAccount::getSubjectType, type)
                                .eq(DatasourceSubjectAccount::getSubjectId, subjectId));
        if (account == null) {
            throw new IllegalArgumentException(
                    "未配置" + ("USER".equals(type) ? "用户" : "服务账号") + "的数据源账号映射");
        }
        Long uncertain = permissions.countUncertain(sourceId, type, subjectId);
        if (uncertain != null && uncertain > 0) {
            throw new IllegalArgumentException("该成员或所属角色的原生权限结果待核对，请联系管理员查看授权记录");
        }
        return account;
    }

    public String currentUserId() {
        CurrentPrincipal context = PrincipalContextHolder.getContext();
        if (context == null || "anonymous".equals(context.getAuthSource())) {
            throw new IllegalArgumentException("请登录后访问数据源");
        }
        SysUser user = userMapper.selectById(context.getUserId());
        requireEnabled(user);
        return String.valueOf(user.getId());
    }

    public SysUser resolveUser(String identity) {
        int separator = identity == null ? -1 : identity.indexOf(':');
        if (separator <= 0 || separator == identity.length() - 1) {
            throw new IllegalArgumentException("缺少有效的用户标识");
        }
        String source = identity.substring(0, separator);
        String subject = identity.substring(separator + 1);
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<>();
        // 来源前缀决定同一查询使用的标识字段；不按用户名猜测 OAuth 身份。
        boolean local = "local".equals(source);
        query.eq(local ? SysUser::getUsername : SysUser::getExternalId, subject);
        if (local) {
            query.eq(SysUser::getAuthSource, "local");
        } else {
            query.eq(SysUser::getAuthSource, source);
        }
        SysUser user = userMapper.selectOne(query);
        requireEnabled(user);
        if (user.isServiceAccount()) throw new IllegalArgumentException("代用户身份必须为普通用户");
        return user;
    }

    private void validateSubject(String type, String id) {
        if (!"USER".equals(type) && !"SERVICE".equals(type))
            throw new IllegalArgumentException("主体类型必须为USER或SERVICE");
        final Long key;
        try {
            key = Long.valueOf(id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("请选择平台已有账号");
        }
        SysUser user = userMapper.selectById(key);
        requireEnabled(user);
        if ("SERVICE".equals(type) != user.isServiceAccount())
            throw new IllegalArgumentException("账号类型与授权主体不一致");
    }

    private void requireEnabled(SysUser user) {
        if (user == null
                || !Boolean.TRUE.equals(user.getEnabled())
                || Integer.valueOf(1).equals(user.getDeleted())) {
            throw new IllegalArgumentException("用户不存在或已停用");
        }
    }

    public Connection openPrincipalConnection(Long sourceId, String database, boolean readOnly)
            throws SQLException {
        CurrentPrincipal p = PrincipalContextHolder.require();
        return open(sourceId, p.getAccountType().name(), p.getUserId(), database, readOnly);
    }

    public Connection openSystemConnection(Long sourceId, String database) throws SQLException {
        DorisCluster c = clusterMapper.selectById(sourceId);
        if (c == null) throw new IllegalArgumentException("数据源不存在");
        return connectionFactory.open(
                c.getSourceType(),
                ConnectionConfig.from(c),
                database,
                c.getUsername(),
                c.getPassword(),
                false);
    }

    public void executeAsPrincipal(Long sourceId, String database, String sql) {
        try (Connection c = openPrincipalConnection(sourceId, database, false);
                java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalArgumentException("主体数据库操作失败", e);
        }
    }

    public Connection open(
            Long sourceId, String type, String subjectId, String namespace, boolean readOnly)
            throws SQLException {
        DorisCluster cluster = clusterMapper.selectById(sourceId);
        if (cluster == null) {
            throw new IllegalArgumentException("数据源不存在");
        }
        DatasourceSubjectAccount account = require(sourceId, type, subjectId);
        return connectionFactory.open(
                cluster.getSourceType(),
                ConnectionConfig.from(cluster),
                namespace,
                account.getUsername(),
                Boolean.TRUE.equals(account.getManaged())
                        ? credentialCipher.decrypt(account.getPassword())
                        : account.getPassword(),
                readOnly);
    }

    private final DatasourceAdapterRegistry adapters;

    public boolean isVisible(Long sourceId, String database, String table) {
        return visibleColumns(sourceId).containsKey(restrictedKey(database, table));
    }

    public boolean isColumnVisible(Long sourceId, String database, String table, String column) {
        Set<String> columns = visibleColumns(sourceId).get(restrictedKey(database, table));
        return columns != null && columns.contains(column);
    }

    private Map<String, Set<String>> visibleColumns(Long sourceId) {
        CurrentPrincipal p = PrincipalContextHolder.require();
        return PrincipalContextHolder.cached(
                java.util.List.of(
                        sourceId,
                        p.getSubjectId(),
                        p.getAccountType(),
                        java.util.Objects.toString(p.getDelegatedUserKey(), "")),
                () -> loadVisibleColumns(sourceId));
    }

    private Map<String, Set<String>> loadVisibleColumns(Long sourceId) {
        CurrentPrincipal identity = PrincipalContextHolder.require();
        DorisCluster cluster = clusterMapper.selectById(sourceId);
        if (cluster == null) {
            return new LinkedHashMap<>();
        }
        DatasourceMetadataReader reader = adapters.require(cluster.getSourceType()).metadata();
        try (Connection agent =
                open(
                        sourceId,
                        identity.getAccountType().name(),
                        identity.getUserId(),
                        null,
                        true)) {
            Map<String, Set<String>> result = enumerate(reader, agent);
            if (identity.getDelegatedUserKey() == null) return result;
            SysUser user = resolveUser(identity.getDelegatedUserKey());
            try (Connection person =
                    open(sourceId, "USER", String.valueOf(user.getId()), null, true)) {
                Map<String, Set<String>> visible = enumerate(reader, person);
                result.keySet().retainAll(visible.keySet());
                result.forEach(
                        (restrictedKey, columns) -> columns.retainAll(visible.get(restrictedKey)));
            }
            return result;
        } catch (SQLException | IllegalArgumentException e) {
            // 无映射/不可连接的源不出现在模型的目录中；实际查询仍返回明确拒绝原因。
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Set<String>> enumerate(
            DatasourceMetadataReader reader, Connection connection) {
        return reader.visibleColumns(connection);
    }

    private String restrictedKey(String namespace, String table) {
        return namespace + "\u0000" + table;
    }

    public void checkReadAccess(Long sourceId, String database, String sql, int timeout) {
        CurrentPrincipal p = PrincipalContextHolder.require();
        if (p.getAccountType() != CurrentPrincipal.AccountType.SERVICE
                || p.getDelegatedUserKey() == null) return;
        SysUser user = resolveUser(p.getDelegatedUserKey());
        try (Connection c = open(sourceId, "USER", String.valueOf(user.getId()), database, true);
                java.sql.Statement st = c.createStatement()) {
            st.setQueryTimeout(timeout);
            try (java.sql.ResultSet ignored = st.executeQuery("EXPLAIN " + sql)) {}
        } catch (SQLException e) {
            throw new IllegalArgumentException("用户数据库权限检查失败", e);
        }
    }

    public String fetchTableDdl(
            com.onedata.portal.dto.DatasourceResolutionDto ds,
            String database,
            String table,
            int timeout) {
        DorisCluster cluster = clusterMapper.selectById(ds.getClusterId());
        var adapter = adapters.require(cluster.getSourceType());
        if (!(adapter
                instanceof
                com.onedata.portal.service.datasource.spi.DatasourceReferenceDdlReader reader))
            throw new IllegalArgumentException("数据源不支持参考结构读取");
        checkReadAccess(
                ds.getClusterId(),
                database,
                adapter.dialect().defaultQuery(database, table, 1),
                timeout);
        try (Connection c = openPrincipalConnection(ds.getClusterId(), database, true)) {
            return reader.referenceDdl(c, database, table, timeout);
        } catch (SQLException e) {
            throw new IllegalArgumentException("读取表结构失败", e);
        }
    }

    public DatasourceResolutionDto resolveDatasource(String database, String preferredEngine) {
        String targetDatabase = trimToNull(database);
        if (!StringUtils.hasText(targetDatabase)) {
            throw new IllegalArgumentException("database 不能为空");
        }

        String normalizedPreferredEngine = trimToNull(preferredEngine);
        if (StringUtils.hasText(normalizedPreferredEngine)) {
            normalizedPreferredEngine = normalizedPreferredEngine.toLowerCase(Locale.ROOT);
        }

        Long clusterId = resolveClusterIdFromTables(targetDatabase);
        if (clusterId == null) {
            throw new IllegalArgumentException("未找到 namespace 对应的数据源，请先同步元数据");
        }

        return resolveClusterDatasource(
                clusterId, targetDatabase, normalizedPreferredEngine, "subject_mapping");
    }

    public DatasourceResolutionDto resolveClusterDatasource(
            Long clusterId,
            String targetDatabase,
            String normalizedPreferredEngine,
            String resolvedBy) {
        DorisCluster cluster = clusterMapper.selectById(clusterId);
        if (cluster == null) {
            throw new IllegalArgumentException("cluster_id `" + clusterId + "` 不存在");
        }

        String sourceType = normalizeSourceType(cluster.getSourceType());
        String engine = engineForSourceType(sourceType);
        ensurePreferredEngine(targetDatabase, normalizedPreferredEngine, engine);

        DatasourceResolutionDto response = new DatasourceResolutionDto();
        response.setEngine(engine);
        response.setDatabase(targetDatabase);
        response.setSourceType(sourceType);
        response.setClusterId(cluster.getId());
        response.setClusterName(trimToEmpty(cluster.getClusterName()));
        response.setResolvedBy(resolvedBy);
        require(
                response.getClusterId(),
                com.onedata.auth.context.PrincipalContextHolder.require().getAccountType().name(),
                com.onedata.auth.context.PrincipalContextHolder.require().getUserId());
        return response;
    }

    private Long resolveClusterIdFromTables(String database) {
        // 不同数据源可能有同名 database（典型如多个 PostgreSQL 的 public），先按授权范围收窄再判冲突
        List<DataTable> tables =
                dataTableMapper.selectList(
                        new QueryWrapper<DataTable>()
                                .select("DISTINCT cluster_id")
                                .eq("db_name", database)
                                .ne("status", "deprecated"));
        Set<Long> clusterIds =
                tables.stream()
                        .map(DataTable::getClusterId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (clusterIds.size() > 1) {
            throw new IllegalArgumentException(
                    "database `" + database + "` 命中了多个 cluster_id " + clusterIds + "，请通过数据源 ID 定位");
        }
        return clusterIds.stream().findFirst().orElse(null);
    }

    private void ensurePreferredEngine(
            String database, String preferredEngine, String actualEngine) {
        if (StringUtils.hasText(preferredEngine) && !preferredEngine.equals(actualEngine)) {
            throw new IllegalArgumentException(
                    "database `" + database + "` 与 " + preferredEngine + " 引擎不匹配");
        }
    }

    public String engineForSourceType(String sourceType) {
        String engine = adapters.require(sourceType).descriptor().getAgentEngine();
        if (!StringUtils.hasText(engine)) {
            throw new IllegalArgumentException("数据源类型 " + sourceType + " 暂不支持智能查询");
        }
        return engine;
    }

    public String normalizeSourceType(String sourceType) {
        return DatasourceAdapterRegistry.normalize(sourceType);
    }

    private String trimToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public void executeAsSystem(Long sourceId, String database, String sql) {
        try (Connection c = openSystemConnection(sourceId, database);
                java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalArgumentException("系统数据库操作失败", e);
        }
    }
}
