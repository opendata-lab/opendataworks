package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.onedata.portal.dto.backup.SchemaBackupConfigRequest;
import com.onedata.portal.dto.backup.SchemaBackupItem;
import com.onedata.portal.dto.backup.SchemaBackupRestoreRequest;
import com.onedata.portal.dto.backup.SchemaBackupRestoreResponse;
import com.onedata.portal.dto.backup.SchemaBackupSnapshot;
import com.onedata.portal.dto.backup.SchemaBackupTriggerResponse;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.MinioConfig;
import com.onedata.portal.entity.SchemaBackupConfig;
import com.onedata.portal.mapper.SchemaBackupConfigMapper;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Schema 备份服务（Doris + MinIO）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaBackupService {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]+$");
    private static final Pattern PATH_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-./]+$");
    private static final DateTimeFormatter SNAPSHOT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DAILY_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String DEFAULT_BACKUP_TIME = "02:00";

    private final DatasourceAdapterRegistry adapterRegistry;
    private final SchemaBackupConfigMapper schemaBackupConfigMapper;
    private final DorisClusterService dorisClusterService;
    private final DorisConnectionService dorisConnectionService;
    private final MinioConfigService minioConfigService;

    public List<SchemaBackupItem> listSchemaBackupItems(Long clusterId) {
        requireDorisCluster(clusterId);

        List<String> schemas = dorisConnectionService.getAllDatabases(clusterId);
        Map<Long, MinioConfig> minioConfigMap = minioConfigService.listAll(null).stream()
                .collect(Collectors.toMap(MinioConfig::getId, cfg -> cfg, (a, b) -> a, HashMap::new));
        Map<String, SchemaBackupConfig> configMap = schemaBackupConfigMapper.selectList(
                new LambdaQueryWrapper<SchemaBackupConfig>()
                        .eq(SchemaBackupConfig::getClusterId, clusterId))
                .stream()
                .collect(Collectors.toMap(
                        cfg -> normalizeSchemaName(cfg.getSchemaName()),
                        cfg -> cfg,
                        (a, b) -> a,
                        HashMap::new));

        List<SchemaBackupItem> result = new ArrayList<>();
        for (String schema : schemas) {
            result.add(toItem(clusterId, schema, configMap.remove(normalizeSchemaName(schema)), minioConfigMap));
        }

        // 保留配置中存在但 Doris 当前未返回的 schema，避免配置“丢失”。
        for (SchemaBackupConfig remaining : configMap.values()) {
            result.add(toItem(clusterId, remaining.getSchemaName(), remaining, minioConfigMap));
        }

        result.sort(Comparator.comparing(item -> item.getSchemaName().toLowerCase(Locale.ROOT)));
        return result;
    }

    public SchemaBackupItem getSchemaBackupItem(Long clusterId, String schemaName) {
        requireDorisCluster(clusterId);
        String normalizedSchema = normalizeSchemaName(schemaName);
        SchemaBackupConfig config = findConfig(clusterId, normalizedSchema);
        Map<Long, MinioConfig> minioConfigMap = minioConfigService.listAll(null).stream()
                .collect(Collectors.toMap(MinioConfig::getId, cfg -> cfg, (a, b) -> a, HashMap::new));
        return toItem(clusterId, normalizedSchema, config, minioConfigMap);
    }

    @Transactional
    public SchemaBackupItem upsertConfig(Long clusterId, String schemaName, SchemaBackupConfigRequest request) {
        requireDorisCluster(clusterId);
        String normalizedSchema = normalizeSchemaName(schemaName);
        requireSchemaExists(clusterId, normalizedSchema);

        SchemaBackupConfig exists = findConfig(clusterId, normalizedSchema);
        SchemaBackupConfig target = exists != null ? exists : new SchemaBackupConfig();

        if (exists == null) {
            target.setClusterId(clusterId);
            target.setSchemaName(normalizedSchema);
            target.setBackupEnabled(0);
            target.setStatus("active");
            target.setUsePathStyle(1);
            target.setBackupTime(DEFAULT_BACKUP_TIME);
        }

        String repositoryName = resolveOptional(
                request == null ? null : request.getRepositoryName(),
                target.getRepositoryName());
        if (!StringUtils.hasText(repositoryName)) {
            repositoryName = buildDefaultRepositoryName(clusterId, normalizedSchema);
        }
        repositoryName = normalizeIdentifier(repositoryName, "repositoryName");
        target.setRepositoryName(repositoryName);

        Long minioConfigId = request != null && request.getMinioConfigId() != null
                ? request.getMinioConfigId()
                : target.getMinioConfigId();
        if (minioConfigId != null) {
            MinioConfig minioConfig = minioConfigService.getEnabledById(minioConfigId);
            if (minioConfig == null) {
                throw new IllegalArgumentException("MinIO 环境不存在: " + minioConfigId);
            }
            target.setMinioConfigId(minioConfigId);
            target.setMinioEndpoint(trimTrailingSlash(minioConfig.getEndpoint()));
            target.setMinioRegion(StringUtils.hasText(minioConfig.getRegion()) ? minioConfig.getRegion().trim() : DEFAULT_REGION);
            target.setMinioAccessKey(minioConfig.getAccessKey());
            target.setMinioSecretKey(minioConfig.getSecretKey());
            target.setUsePathStyle(minioConfig.getUsePathStyle() != null && minioConfig.getUsePathStyle() == 0 ? 0 : 1);
        } else {
            target.setMinioConfigId(null);
            String endpoint = resolveRequired(
                    request == null ? null : request.getMinioEndpoint(),
                    target.getMinioEndpoint(),
                    "minioEndpoint");
            target.setMinioEndpoint(trimTrailingSlash(endpoint));

            String region = resolveOptional(
                    request == null ? null : request.getMinioRegion(),
                    target.getMinioRegion());
            target.setMinioRegion(StringUtils.hasText(region) ? region.trim() : DEFAULT_REGION);

            String accessKey = resolveRequired(
                    request == null ? null : request.getMinioAccessKey(),
                    target.getMinioAccessKey(),
                    "minioAccessKey");
            target.setMinioAccessKey(accessKey.trim());

            String secretKey = resolveRequired(
                    request == null ? null : request.getMinioSecretKey(),
                    target.getMinioSecretKey(),
                    "minioSecretKey");
            target.setMinioSecretKey(secretKey.trim());
        }

        String bucket = resolveRequired(
                request == null ? null : request.getMinioBucket(),
                target.getMinioBucket(),
                "minioBucket");
        target.setMinioBucket(validateBucket(bucket.trim()));

        String basePath = resolveRequired(
                request == null ? null : request.getMinioBasePath(),
                target.getMinioBasePath(),
                "minioBasePath");
        target.setMinioBasePath(normalizePath(basePath));

        if (minioConfigId == null) {
            Integer usePathStyle = request == null ? null : request.getUsePathStyle();
            if (usePathStyle != null) {
                target.setUsePathStyle(usePathStyle == 0 ? 0 : 1);
            }
            if (target.getUsePathStyle() == null) {
                target.setUsePathStyle(1);
            }
        }

        Integer backupEnabled = request == null ? null : request.getBackupEnabled();
        if (backupEnabled != null) {
            target.setBackupEnabled(backupEnabled == 1 ? 1 : 0);
        }
        if (target.getBackupEnabled() == null) {
            target.setBackupEnabled(0);
        }

        String backupTime = resolveOptional(
                request == null ? null : request.getBackupTime(),
                target.getBackupTime());
        if (!StringUtils.hasText(backupTime)) {
            backupTime = DEFAULT_BACKUP_TIME;
        }
        target.setBackupTime(parseDailyTime(backupTime).format(DAILY_TIME_FORMATTER));

        String status = resolveOptional(request == null ? null : request.getStatus(), target.getStatus());
        if (!StringUtils.hasText(status)) {
            status = "active";
        }
        status = status.trim().toLowerCase(Locale.ROOT);
        if (!"active".equals(status) && !"inactive".equals(status)) {
            throw new IllegalArgumentException("status 仅支持 active / inactive");
        }
        target.setStatus(status);

        if (exists == null) {
            schemaBackupConfigMapper.insert(target);
        } else {
            schemaBackupConfigMapper.updateById(target);
        }

        Map<Long, MinioConfig> minioConfigMap = minioConfigService.listAll(null).stream()
                .collect(Collectors.toMap(MinioConfig::getId, cfg -> cfg, (a, b) -> a, HashMap::new));
        return toItem(clusterId, normalizedSchema, schemaBackupConfigMapper.selectById(target.getId()), minioConfigMap);
    }

    @Transactional
    public SchemaBackupTriggerResponse triggerBackup(Long clusterId, String schemaName, String triggerType) {
        requireDorisCluster(clusterId);
        String normalizedSchema = normalizeSchemaName(schemaName);
        requireSchemaExists(clusterId, normalizedSchema);
        SchemaBackupConfig config = requireConfig(clusterId, normalizedSchema);
        return triggerBackupInternal(config, StringUtils.hasText(triggerType) ? triggerType : "manual");
    }

    public List<SchemaBackupSnapshot> listSnapshots(Long clusterId, String schemaName) {
        requireDorisCluster(clusterId);
        String normalizedSchema = normalizeSchemaName(schemaName);
        SchemaBackupConfig config = requireConfig(clusterId, normalizedSchema);

        String sql = "SHOW SNAPSHOT ON `" + config.getRepositoryName() + "`";
        List<Map<String, Object>> rows = query(clusterId, null, sql);
        List<SchemaBackupSnapshot> snapshots = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String database = firstText(row, "database", "db", "database_name");
            if (StringUtils.hasText(database) && !normalizedSchema.equalsIgnoreCase(database.trim())) {
                continue;
            }
            String snapshotName = firstText(row, "snapshot", "snapshot_name");
            if (!StringUtils.hasText(snapshotName)) {
                continue;
            }
            SchemaBackupSnapshot snapshot = new SchemaBackupSnapshot();
            snapshot.setSnapshotName(snapshotName);
            snapshot.setBackupTimestamp(firstText(row, "timestamp", "backup_timestamp"));
            snapshot.setStatus(firstText(row, "status", "state"));
            snapshot.setDatabaseName(database);
            snapshot.setDetails(firstText(row, "details", "detail", "meta"));
            snapshots.add(snapshot);
        }

        snapshots.sort((a, b) -> {
            String left = a.getBackupTimestamp() == null ? "" : a.getBackupTimestamp();
            String right = b.getBackupTimestamp() == null ? "" : b.getBackupTimestamp();
            return right.compareTo(left);
        });
        return snapshots;
    }

    public SchemaBackupRestoreResponse restoreSnapshot(Long clusterId, String schemaName, SchemaBackupRestoreRequest request) {
        requireDorisCluster(clusterId);
        String normalizedSchema = normalizeSchemaName(schemaName);
        requireSchemaExists(clusterId, normalizedSchema);
        SchemaBackupConfig config = requireConfig(clusterId, normalizedSchema);

        if (request == null) {
            throw new IllegalArgumentException("恢复请求不能为空");
        }
        String snapshotName = normalizeIdentifier(request.getSnapshotName(), "snapshotName");
        String backupTimestamp = normalizeBackupTimestamp(request.getBackupTimestamp());
        String tableName = StringUtils.hasText(request.getTableName())
                ? normalizeIdentifier(request.getTableName(), "tableName")
                : null;

        StringBuilder sql = new StringBuilder();
        sql.append("RESTORE SNAPSHOT `").append(normalizedSchema).append("`.`").append(snapshotName).append("` ")
                .append("FROM `").append(config.getRepositoryName()).append("` ");
        if (StringUtils.hasText(tableName)) {
            sql.append("ON (`").append(tableName).append("`) ");
        }
        sql.append("PROPERTIES(\"backup_timestamp\" = \"")
                .append(escapeDoubleQuotes(backupTimestamp))
                .append("\")");

        dorisConnectionService.execute(clusterId, sql.toString());

        SchemaBackupRestoreResponse response = new SchemaBackupRestoreResponse();
        response.setSchemaName(normalizedSchema);
        response.setSnapshotName(snapshotName);
        response.setBackupTimestamp(backupTimestamp);
        response.setTableName(tableName);
        response.setRepositoryName(config.getRepositoryName());
        response.setSubmittedAt(LocalDateTime.now());
        return response;
    }

    public List<SchemaBackupConfig> listEnabledConfigs() {
        return schemaBackupConfigMapper.selectList(
                new LambdaQueryWrapper<SchemaBackupConfig>()
                        .eq(SchemaBackupConfig::getBackupEnabled, 1)
                        .eq(SchemaBackupConfig::getStatus, "active")
                        .orderByAsc(SchemaBackupConfig::getClusterId)
                        .orderByAsc(SchemaBackupConfig::getSchemaName));
    }

    public boolean shouldRunNow(SchemaBackupConfig config, LocalDateTime now) {
        if (config == null || config.getBackupEnabled() == null || config.getBackupEnabled() != 1 || now == null) {
            return false;
        }
        LocalTime targetTime;
        try {
            targetTime = parseDailyTime(config.getBackupTime());
        } catch (Exception e) {
            log.warn("Invalid backupTime in schema backup config id={}, clusterId={}, schema={}, backupTime={}",
                    config.getId(), config.getClusterId(), config.getSchemaName(), config.getBackupTime());
            return false;
        }
        LocalDateTime todayTarget = LocalDateTime.of(now.toLocalDate(), targetTime);
        if (now.isBefore(todayTarget)) {
            return false;
        }
        LocalDateTime lastBackupTime = config.getLastBackupTime();
        if (lastBackupTime == null) {
            return true;
        }
        LocalDate lastDate = lastBackupTime.toLocalDate();
        return lastDate.isBefore(now.toLocalDate());
    }

    @Transactional
    public SchemaBackupTriggerResponse triggerBackupByConfig(SchemaBackupConfig config, String triggerType) {
        if (config == null || config.getClusterId() == null || !StringUtils.hasText(config.getSchemaName())) {
            throw new IllegalArgumentException("无效的 schema 备份配置");
        }
        SchemaBackupConfig latest = requireConfig(config.getClusterId(), normalizeSchemaName(config.getSchemaName()));
        return triggerBackupInternal(latest, StringUtils.hasText(triggerType) ? triggerType : "auto");
    }

    private SchemaBackupTriggerResponse triggerBackupInternal(SchemaBackupConfig config, String triggerType) {
        if (config.getBackupEnabled() != null && config.getBackupEnabled() == 0 && "auto".equalsIgnoreCase(triggerType)) {
            throw new IllegalStateException("当前 schema 未开启自动备份");
        }
        MinioProfile profile = resolveMinioProfile(config);
        ensureRepository(config, profile);

        String schemaName = normalizeSchemaName(config.getSchemaName());
        String snapshotName = buildSnapshotName(schemaName, triggerType);
        String sql = "BACKUP SNAPSHOT `" + schemaName + "`.`" + snapshotName + "` TO `" + config.getRepositoryName() + "`";
        dorisConnectionService.execute(config.getClusterId(), sql);

        LocalDateTime now = LocalDateTime.now();
        schemaBackupConfigMapper.update(null, new LambdaUpdateWrapper<SchemaBackupConfig>()
                .eq(SchemaBackupConfig::getId, config.getId())
                .set(SchemaBackupConfig::getLastBackupTime, now));

        SchemaBackupTriggerResponse response = new SchemaBackupTriggerResponse();
        response.setSchemaName(schemaName);
        response.setRepositoryName(config.getRepositoryName());
        response.setSnapshotName(snapshotName);
        response.setTriggerType(triggerType);
        response.setSubmittedAt(now);
        return response;
    }

    private void ensureRepository(SchemaBackupConfig config, MinioProfile profile) {
        String location = buildS3Location(config.getMinioBucket(), config.getMinioBasePath());
        String modernSql = buildRepositorySql(config, profile, location, false);
        try {
            dorisConnectionService.execute(config.getClusterId(), modernSql);
            return;
        } catch (RuntimeException modernError) {
            log.warn("Create repository with s3.* properties failed, fallback to AWS_* properties. repo={}, clusterId={}",
                    config.getRepositoryName(), config.getClusterId(), modernError);
        }

        String legacySql = buildRepositorySql(config, profile, location, true);
        dorisConnectionService.execute(config.getClusterId(), legacySql);
    }

    private String buildRepositorySql(SchemaBackupConfig config, MinioProfile profile, String location, boolean legacy) {
        String endpointKey = legacy ? "AWS_ENDPOINT" : "s3.endpoint";
        String regionKey = legacy ? "AWS_REGION" : "s3.region";
        String accessKey = legacy ? "AWS_ACCESS_KEY" : "s3.access_key";
        String secretKey = legacy ? "AWS_SECRET_KEY" : "s3.secret_key";
        return "CREATE REPOSITORY IF NOT EXISTS `" + config.getRepositoryName() + "`\n"
                + "WITH S3\n"
                + "ON LOCATION \"" + escapeDoubleQuotes(location) + "\"\n"
                + "PROPERTIES(\n"
                + "\"" + endpointKey + "\" = \"" + escapeDoubleQuotes(profile.endpoint) + "\",\n"
                + "\"" + regionKey + "\" = \"" + escapeDoubleQuotes(resolveRegion(profile.region)) + "\",\n"
                + "\"" + accessKey + "\" = \"" + escapeDoubleQuotes(profile.accessKey) + "\",\n"
                + "\"" + secretKey + "\" = \"" + escapeDoubleQuotes(profile.secretKey) + "\",\n"
                + "\"use_path_style\" = \"" + (profile.usePathStyle == 0 ? "false" : "true") + "\"\n"
                + ")";
    }

    private String buildS3Location(String bucket, String basePath) {
        String safeBucket = validateBucket(bucket);
        String safePath = normalizePath(basePath);
        return "s3://" + safeBucket + "/" + safePath;
    }

    private String resolveRegion(String region) {
        return StringUtils.hasText(region) ? region.trim() : DEFAULT_REGION;
    }

    private List<Map<String, Object>> query(Long clusterId, String database, String sql) {
        try (Connection connection = StringUtils.hasText(database)
                ? dorisConnectionService.getConnection(clusterId, database)
                : dorisConnectionService.getConnection(clusterId);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {

            List<Map<String, Object>> rows = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int count = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= count; i++) {
                    String label = metaData.getColumnLabel(i);
                    Object value = rs.getObject(i);
                    row.put(label, value);
                    if (StringUtils.hasText(label)) {
                        row.put(label.toLowerCase(Locale.ROOT), value);
                    }
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("执行 Doris 查询失败: " + e.getMessage(), e);
        }
    }

    private DorisCluster requireDorisCluster(Long clusterId) {
        DorisCluster cluster = dorisClusterService.getById(clusterId);
        if (cluster == null) {
            throw new IllegalArgumentException("数据源不存在: " + clusterId);
        }
        adapterRegistry.requireCapability(cluster.getSourceType(), "backup");
        return cluster;
    }

    private void requireSchemaExists(Long clusterId, String schemaName) {
        List<String> schemas = dorisConnectionService.getAllDatabases(clusterId);
        for (String db : schemas) {
            if (schemaName.equalsIgnoreCase(normalizeSchemaName(db))) {
                return;
            }
        }
        throw new IllegalArgumentException("Schema 不存在: " + schemaName);
    }

    private SchemaBackupConfig requireConfig(Long clusterId, String schemaName) {
        SchemaBackupConfig config = findConfig(clusterId, schemaName);
        if (config == null) {
            throw new IllegalStateException("请先配置 schema 备份参数");
        }
        if (!StringUtils.hasText(config.getRepositoryName())) {
            throw new IllegalStateException("repositoryName 未配置");
        }
        resolveMinioProfile(config);
        if (!StringUtils.hasText(config.getMinioBucket()) || !StringUtils.hasText(config.getMinioBasePath())) {
            throw new IllegalStateException("MinIO Bucket 与路径未配置");
        }
        return config;
    }

    private SchemaBackupConfig findConfig(Long clusterId, String schemaName) {
        return schemaBackupConfigMapper.selectOne(
                new LambdaQueryWrapper<SchemaBackupConfig>()
                        .eq(SchemaBackupConfig::getClusterId, clusterId)
                        .eq(SchemaBackupConfig::getSchemaName, schemaName)
                        .last("LIMIT 1"));
    }

    private SchemaBackupItem toItem(Long clusterId, String schemaName, SchemaBackupConfig config,
            Map<Long, MinioConfig> minioConfigMap) {
        SchemaBackupItem item = new SchemaBackupItem();
        item.setClusterId(clusterId);
        item.setSchemaName(schemaName);
        if (config == null) {
            item.setHasConfig(0);
            item.setBackupEnabled(0);
            item.setStatus("inactive");
            return item;
        }

        item.setHasConfig(1);
        item.setRepositoryName(config.getRepositoryName());
        item.setMinioConfigId(config.getMinioConfigId());
        MinioConfig minioConfig = config.getMinioConfigId() == null ? null : minioConfigMap.get(config.getMinioConfigId());
        if (minioConfig != null) {
            item.setMinioConfigName(minioConfig.getConfigName());
            item.setMinioEndpoint(minioConfig.getEndpoint());
            item.setMinioRegion(minioConfig.getRegion());
            item.setUsePathStyle(minioConfig.getUsePathStyle());
            item.setHasAccessKey(StringUtils.hasText(minioConfig.getAccessKey()) ? 1 : 0);
            item.setHasSecretKey(StringUtils.hasText(minioConfig.getSecretKey()) ? 1 : 0);
        } else {
            item.setMinioConfigName(null);
            item.setMinioEndpoint(config.getMinioEndpoint());
            item.setMinioRegion(config.getMinioRegion());
            item.setUsePathStyle(config.getUsePathStyle());
            item.setHasAccessKey(StringUtils.hasText(config.getMinioAccessKey()) ? 1 : 0);
            item.setHasSecretKey(StringUtils.hasText(config.getMinioSecretKey()) ? 1 : 0);
        }
        item.setMinioBucket(config.getMinioBucket());
        item.setMinioBasePath(config.getMinioBasePath());
        item.setBackupEnabled(config.getBackupEnabled() != null ? config.getBackupEnabled() : 0);
        item.setBackupTime(config.getBackupTime());
        item.setLastBackupTime(config.getLastBackupTime());
        item.setStatus(config.getStatus());
        return item;
    }

    private MinioProfile resolveMinioProfile(SchemaBackupConfig config) {
        if (config == null) {
            throw new IllegalStateException("Schema 备份配置不存在");
        }
        if (config.getMinioConfigId() != null) {
            MinioConfig minioConfig = minioConfigService.getEnabledById(config.getMinioConfigId());
            if (minioConfig == null) {
                throw new IllegalStateException("关联的 MinIO 环境不存在: " + config.getMinioConfigId());
            }
            MinioProfile profile = new MinioProfile();
            profile.endpoint = trimTrailingSlash(minioConfig.getEndpoint());
            profile.region = StringUtils.hasText(minioConfig.getRegion()) ? minioConfig.getRegion().trim() : DEFAULT_REGION;
            profile.accessKey = minioConfig.getAccessKey();
            profile.secretKey = minioConfig.getSecretKey();
            profile.usePathStyle = minioConfig.getUsePathStyle() != null && minioConfig.getUsePathStyle() == 0 ? 0 : 1;
            validateMinioProfile(profile);
            return profile;
        }
        MinioProfile profile = new MinioProfile();
        profile.endpoint = config.getMinioEndpoint();
        profile.region = config.getMinioRegion();
        profile.accessKey = config.getMinioAccessKey();
        profile.secretKey = config.getMinioSecretKey();
        profile.usePathStyle = config.getUsePathStyle() != null && config.getUsePathStyle() == 0 ? 0 : 1;
        validateMinioProfile(profile);
        return profile;
    }

    private void validateMinioProfile(MinioProfile profile) {
        if (profile == null) {
            throw new IllegalStateException("MinIO 连接配置不存在");
        }
        if (!StringUtils.hasText(profile.endpoint)
                || !StringUtils.hasText(profile.accessKey)
                || !StringUtils.hasText(profile.secretKey)) {
            throw new IllegalStateException("MinIO 连接信息不完整，请检查 MinIO 环境配置");
        }
        profile.endpoint = trimTrailingSlash(profile.endpoint);
        profile.region = resolveRegion(profile.region);
    }

    private String buildSnapshotName(String schemaName, String triggerType) {
        String prefix = normalizeIdentifier(schemaName, "schemaName");
        String trigger = StringUtils.hasText(triggerType) ? triggerType.trim().toLowerCase(Locale.ROOT) : "manual";
        if (!IDENTIFIER_PATTERN.matcher(trigger).matches()) {
            trigger = "manual";
        }
        String ts = SNAPSHOT_TIME_FORMATTER.format(LocalDateTime.now());
        String name = prefix + "_" + trigger + "_" + ts;
        if (name.length() <= 128) {
            return name;
        }
        return name.substring(0, 128);
    }

    private String buildDefaultRepositoryName(Long clusterId, String schemaName) {
        String normalized = normalizeIdentifier(schemaName, "schemaName");
        String name = "repo_" + clusterId + "_" + normalized;
        if (name.length() <= 128) {
            return name;
        }
        return name.substring(0, 128);
    }

    private String normalizeSchemaName(String schemaName) {
        return normalizeIdentifier(schemaName, "schemaName");
    }

    private String normalizeIdentifier(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String normalized = value.trim();
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " 仅支持字母、数字、下划线和中划线");
        }
        return normalized;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("minioBasePath 不能为空");
        }
        String normalized = path.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("minioBasePath 不能为空");
        }
        if (!PATH_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("minioBasePath 包含非法字符");
        }
        return normalized;
    }

    private String normalizeBackupTimestamp(String backupTimestamp) {
        if (!StringUtils.hasText(backupTimestamp)) {
            throw new IllegalArgumentException("backupTimestamp 不能为空");
        }
        String normalized = backupTimestamp.trim();
        if (!normalized.matches("^[0-9:\\-\\s]+$")) {
            throw new IllegalArgumentException("backupTimestamp 格式非法");
        }
        return normalized;
    }

    private String validateBucket(String bucket) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalArgumentException("minioBucket 不能为空");
        }
        String normalized = bucket.trim();
        if (!normalized.matches("^[a-z0-9][a-z0-9.-]{1,62}$")) {
            throw new IllegalArgumentException("minioBucket 格式非法");
        }
        return normalized;
    }

    private LocalTime parseDailyTime(String backupTime) {
        if (!StringUtils.hasText(backupTime)) {
            throw new IllegalArgumentException("backupTime 不能为空");
        }
        String normalized = backupTime.trim();
        try {
            return LocalTime.parse(normalized, DAILY_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("backupTime 格式必须为 HH:mm");
        }
    }

    private String resolveRequired(String incoming, String existing, String field) {
        String resolved = resolveOptional(incoming, existing);
        if (!StringUtils.hasText(resolved)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return resolved;
    }

    private String resolveOptional(String incoming, String existing) {
        if (StringUtils.hasText(incoming)) {
            return incoming.trim();
        }
        return StringUtils.hasText(existing) ? existing.trim() : null;
    }

    private String firstText(Map<String, Object> row, String... keys) {
        if (row == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object value = row.get(key);
            if (value == null) {
                value = row.get(key.toLowerCase(Locale.ROOT));
            }
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String escapeDoubleQuotes(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static class MinioProfile {
        private String endpoint;
        private String region;
        private String accessKey;
        private String secretKey;
        private int usePathStyle = 1;
    }
}
