package com.onedata.portal.service.permission;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DatasourceAccessPermissionMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.ServiceAccountTokenMapper;
import com.onedata.portal.service.PlatformUserManagementService;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Person-centric view over datasource permissions: one account's roles across every datasource.
 * Reads platform tables only; changes still go through the per-source member endpoints.
 */
@Service
@RequiredArgsConstructor
public class SubjectAccessService {
    private static final Set<String> FAILED_STATES = Set.of("FAILED", "PARTIAL", "UNKNOWN");
    private static final Set<String> PERMISSION_ENGINES = Set.of("MYSQL", "POSTGRESQL", "DORIS");

    private final PlatformUserManagementService userService;
    private final DatasourcePermissionService permissionService;
    private final DatasourceAccessPermissionMapper permissions;
    private final ServiceAccountTokenMapper tokens;
    private final DorisClusterMapper clusters;
    private final SysUserMapper users;

    @Data
    @AllArgsConstructor
    public static class UserView {
        @JsonUnwrapped private SysUser user;
        private int activeTokenCount;

        // Stored as UTC (UTC_TIMESTAMP); same wire format as ServiceAccountToken.lastUsedAt.
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
        private LocalDateTime lastTokenUsedAt;

        private List<Map<String, Object>> accessSummary;
    }

    public List<UserView> listUsers(String keyword, Boolean enabled, String accountType) {
        Map<String, Map<String, Object>> tokenStats = new HashMap<>();
        for (Map<String, Object> row : tokens.tokenStats())
            tokenStats.put(String.valueOf(col(row, "user_id")), row);

        // "TYPE:id" -> source id -> {sourceId, clusterName, status, roleNames}
        Map<String, Map<Object, Map<String, Object>>> access = new HashMap<>();
        for (Map<String, Object> row : permissions.listAccessSummaryRows()) {
            String subject = col(row, "subject_type") + ":" + col(row, "subject_id");
            Map<String, Object> entry =
                    access.computeIfAbsent(subject, k -> new LinkedHashMap<>())
                            .computeIfAbsent(
                                    col(row, "source_id"),
                                    k -> {
                                        Map<String, Object> e = new LinkedHashMap<>();
                                        e.put("sourceId", col(row, "source_id"));
                                        e.put("clusterName", col(row, "cluster_name"));
                                        e.put("status", col(row, "status"));
                                        e.put("roleNames", new ArrayList<String>());
                                        return e;
                                    });
            Object role = col(row, "role_name");
            if (role != null) roleNames(entry).add(String.valueOf(role));
        }

        List<UserView> result = new ArrayList<>();
        for (SysUser user : userService.list(keyword, enabled, accountType)) {
            String id = String.valueOf(user.getId());
            String key = ("SERVICE".equals(user.getAccountType()) ? "SERVICE" : "USER") + ":" + id;
            Map<String, Object> stats = tokenStats.get(id);
            Object lastUsed = stats == null ? null : col(stats, "last_used_at");
            result.add(
                    new UserView(
                            user,
                            stats == null ? 0 : ((Number) col(stats, "active_count")).intValue(),
                            lastUsed instanceof java.sql.Timestamp
                                    ? ((java.sql.Timestamp) lastUsed).toLocalDateTime()
                                    : (LocalDateTime) lastUsed,
                            new ArrayList<>(
                                    access.getOrDefault(key, Collections.emptyMap()).values())));
        }
        return result;
    }

    /** One account's membership, roles to choose from and latest failure, per datasource. */
    public Map<String, Object> access(Long userId) {
        SysUser user = users.selectById(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        String type = "SERVICE".equals(user.getAccountType()) ? "SERVICE" : "USER";
        String subject = String.valueOf(userId);

        List<Map<String, Object>> sources = new ArrayList<>();
        for (DorisCluster cluster : clusters.selectList(null)) {
            String engine =
                    cluster.getSourceType() == null
                            ? "DORIS"
                            : cluster.getSourceType().toUpperCase(Locale.ROOT);
            if (!PERMISSION_ENGINES.contains(engine)) continue;
            Map<String, Object> overview = permissionService.overview(cluster.getId());
            Map<String, Object> member = null;
            for (Map<String, Object> m : list(overview.get("members")))
                if (type.equals(m.get("subjectType"))
                        && subject.equals(String.valueOf(m.get("subjectId")))) member = m;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("sourceId", cluster.getId());
            source.put("clusterName", cluster.getClusterName());
            source.put("engine", overview.get("engine"));
            source.put(
                    "managementConfigured",
                    ((Map<?, ?>) overview.get("management")).get("configured"));
            source.put("roles", overview.get("roles"));
            source.put("member", member);
            source.put("lastFailedOperation", lastFailure(cluster.getId(), type, subject));
            sources.add(source);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("accountType", type);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("enabled", user.getEnabled());
        result.put("sources", sources);
        return result;
    }

    private Map<String, Object> lastFailure(Long source, String type, String subject) {
        // Operations are newest first; only the latest one for this subject matters.
        for (Map<String, Object> op : permissionService.operations(source)) {
            if (!type.equals(op.get("subjectType"))
                    || !subject.equals(String.valueOf(op.get("subjectId")))) continue;
            return FAILED_STATES.contains(String.valueOf(op.get("state"))) ? op : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value == null ? Collections.emptyList() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> roleNames(Map<String, Object> entry) {
        return (List<String>) entry.get("roleNames");
    }

    /** Platform column labels may come back as snake_case, UPPER_SNAKE or camelCase. */
    private static Object col(Map<String, Object> row, String snake) {
        String flat = snake.replace("_", "");
        for (Map.Entry<String, Object> e : row.entrySet())
            if (e.getKey().replace("_", "").equalsIgnoreCase(flat)) return e.getValue();
        return null;
    }
}
