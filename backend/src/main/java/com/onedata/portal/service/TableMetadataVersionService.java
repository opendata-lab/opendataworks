package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.table.TableVersionCompareRequest;
import com.onedata.portal.dto.table.TableVersionCompareResponse;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTableVersion;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTableVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 表元数据版本服务：仅当白名单元数据（表属性 + 字段列表）真正变化时记录新版本。
 * 版本历史作为审计线索永久保留，本服务不提供删除能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableMetadataVersionService {

    public static final String TRIGGER_TABLE_CREATE = "table_create";
    public static final String TRIGGER_MANUAL_EDIT = "manual_edit";
    public static final String TRIGGER_METADATA_SYNC = "metadata_sync";
    public static final String TRIGGER_INSPECTION_FIX = "inspection_fix";

    private static final int SNAPSHOT_SCHEMA_VERSION = 1;
    private static final int CHANGE_SUMMARY_MAX_LENGTH = 500;

    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final DataTableVersionMapper dataTableVersionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 捕获一次版本：从 DB 重载表与字段（调用方实体可能是部分更新，不可信），
     * 构建规范化快照并与最新版本哈希比对，仅在变化时插入新版本。
     * 任何异常只记日志不上抛——同步路径运行在大事务中，版本记录失败不允许回滚业务写入。
     *
     * @param operator 操作人，传 null 时回退到当前登录用户，再回退到 system
     * @return 新插入的版本；元数据未变化或捕获失败时返回 null
     */
    public DataTableVersion captureVersion(Long tableId, String triggerSource, String operator) {
        if (tableId == null) {
            return null;
        }
        try {
            DataTable table = dataTableMapper.selectById(tableId);
            if (table == null) {
                return null;
            }
            Map<String, Object> snapshot = buildSnapshot(table, loadOrderedFields(tableId));
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            String hash = sha256(canonicalizeJson(objectMapper.readTree(snapshotJson)));

            DataTableVersion latest = selectLatestVersion(tableId);
            if (latest != null && Objects.equals(latest.getSnapshotHash(), hash)) {
                return null;
            }

            DataTableVersion version = new DataTableVersion();
            version.setTableId(tableId);
            version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
            version.setSnapshotHash(hash);
            version.setMetadataSnapshot(snapshotJson);
            version.setChangeSummary(latest == null
                    ? "初始版本快照"
                    : buildChangeSummary(latest.getMetadataSnapshot(), snapshotJson));
            version.setTriggerSource(triggerSource);
            version.setCreatedBy(resolveOperator(operator));
            dataTableVersionMapper.insert(version);
            return version;
        } catch (DuplicateKeyException e) {
            // 手动编辑与定时同步并发捕获同一表时由唯一键兜底，跳过本次记录
            log.warn("Concurrent version capture skipped for table {}", tableId);
            return null;
        } catch (Exception e) {
            log.warn("Failed to capture metadata version for table {}", tableId, e);
            return null;
        }
    }

    /**
     * 分页查询版本列表，不返回快照大字段
     */
    public Page<DataTableVersion> listVersions(Long tableId, int pageNum, int pageSize) {
        Page<DataTableVersion> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DataTableVersion> wrapper = new LambdaQueryWrapper<DataTableVersion>()
                .select(DataTableVersion.class, info -> !"metadata_snapshot".equals(info.getColumn()))
                .eq(DataTableVersion::getTableId, tableId)
                .orderByDesc(DataTableVersion::getVersionNo);
        return dataTableVersionMapper.selectPage(page, wrapper);
    }

    public DataTableVersion getVersion(Long tableId, Long versionId) {
        DataTableVersion version = dataTableVersionMapper.selectById(versionId);
        if (version == null || !Objects.equals(version.getTableId(), tableId)) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }
        return version;
    }

    public TableVersionCompareResponse compare(Long tableId, TableVersionCompareRequest request) {
        if (request == null || request.getLeftVersionId() == null || request.getRightVersionId() == null) {
            throw new IllegalArgumentException("对比需要指定左右两个版本");
        }
        DataTableVersion left = getVersion(tableId, request.getLeftVersionId());
        DataTableVersion right = getVersion(tableId, request.getRightVersionId());
        // 统一让 left 为较旧版本，对比方向稳定
        if (left.getVersionNo() != null && right.getVersionNo() != null
                && left.getVersionNo() > right.getVersionNo()) {
            DataTableVersion tmp = left;
            left = right;
            right = tmp;
        }

        TableVersionCompareResponse response = new TableVersionCompareResponse();
        response.setTableId(tableId);
        response.setLeftVersionId(left.getId());
        response.setLeftVersionNo(left.getVersionNo());
        response.setRightVersionId(right.getId());
        response.setRightVersionNo(right.getVersionNo());

        JsonNode leftRoot = readSnapshot(left.getMetadataSnapshot());
        JsonNode rightRoot = readSnapshot(right.getMetadataSnapshot());

        diffTableAttributes(leftRoot, rightRoot, response);
        diffColumns(leftRoot, rightRoot, response);

        TableVersionCompareResponse.Summary summary = response.getSummary();
        summary.setAttributeChangedCount(response.getTableAttributeChanges().size());
        summary.setColumnsAddedCount(response.getColumnsAdded().size());
        summary.setColumnsRemovedCount(response.getColumnsRemoved().size());
        summary.setColumnsModifiedCount(response.getColumnsModified().size());
        response.setChanged(summary.getAttributeChangedCount() > 0
                || summary.getColumnsAddedCount() > 0
                || summary.getColumnsRemovedCount() > 0
                || summary.getColumnsModifiedCount() > 0);

        response.setRawDiff(buildUnifiedRawDiff(leftRoot, rightRoot, left, right));
        return response;
    }

    // ---------------- 快照构建 ----------------

    private List<DataField> loadOrderedFields(Long tableId) {
        List<DataField> fields = dataFieldMapper.selectList(
                new LambdaQueryWrapper<DataField>()
                        .eq(DataField::getTableId, tableId));
        fields.sort(Comparator
                .comparing(DataField::getFieldOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DataField::getFieldName, Comparator.nullsLast(Comparator.naturalOrder())));
        return fields;
    }

    /**
     * 白名单快照：仅含结构性/描述性元数据。统计与同步类字段
     * （rowCount/storageSize/syncTime/dorisDdl 等）被排除，确保其波动永不产生版本。
     */
    private Map<String, Object> buildSnapshot(DataTable table, List<DataField> fields) {
        Map<String, Object> tableNode = new LinkedHashMap<>();
        tableNode.put("tableName", table.getTableName());
        tableNode.put("dbName", table.getDbName());
        tableNode.put("tableComment", table.getTableComment());
        tableNode.put("tableType", table.getTableType());
        tableNode.put("layer", table.getLayer());
        tableNode.put("businessDomain", table.getBusinessDomain());
        tableNode.put("dataDomain", table.getDataDomain());
        tableNode.put("owner", table.getOwner());
        tableNode.put("status", table.getStatus());
        tableNode.put("tableModel", table.getTableModel());
        tableNode.put("partitionColumn", table.getPartitionColumn());
        tableNode.put("distributionColumn", table.getDistributionColumn());
        tableNode.put("keyColumns", table.getKeyColumns());
        tableNode.put("bucketNum", table.getBucketNum());
        tableNode.put("replicaNum", table.getReplicaNum());

        List<Map<String, Object>> fieldNodes = new ArrayList<>();
        for (DataField field : fields) {
            Map<String, Object> fieldNode = new LinkedHashMap<>();
            fieldNode.put("fieldName", field.getFieldName());
            fieldNode.put("fieldType", field.getFieldType());
            fieldNode.put("fieldComment", field.getFieldComment());
            fieldNode.put("isNullable", field.getIsNullable());
            fieldNode.put("isPrimary", field.getIsPrimary());
            fieldNode.put("isPartition", field.getIsPartition());
            fieldNode.put("defaultValue", field.getDefaultValue());
            fieldNode.put("fieldOrder", field.getFieldOrder());
            fieldNodes.add(fieldNode);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        snapshot.put("table", tableNode);
        snapshot.put("fields", fieldNodes);
        return snapshot;
    }

    private DataTableVersion selectLatestVersion(Long tableId) {
        return dataTableVersionMapper.selectOne(
                new LambdaQueryWrapper<DataTableVersion>()
                        .eq(DataTableVersion::getTableId, tableId)
                        .orderByDesc(DataTableVersion::getVersionNo)
                        .last("limit 1"));
    }

    private String resolveOperator(String operator) {
        if (StringUtils.hasText(operator)) {
            return operator;
        }
        String currentUser = UserContextHolder.getCurrentUserId();
        return StringUtils.hasText(currentUser) ? currentUser : "system";
    }

    // ---------------- 变更摘要 ----------------

    private String buildChangeSummary(String previousSnapshotJson, String currentSnapshotJson) {
        try {
            JsonNode previous = readSnapshot(previousSnapshotJson);
            JsonNode current = readSnapshot(currentSnapshotJson);

            TableVersionCompareResponse diff = new TableVersionCompareResponse();
            diffTableAttributes(previous, current, diff);
            diffColumns(previous, current, diff);

            List<String> parts = new ArrayList<>();
            if (!diff.getTableAttributeChanges().isEmpty()) {
                List<String> names = new ArrayList<>();
                for (TableVersionCompareResponse.AttributeChange change : diff.getTableAttributeChanges()) {
                    names.add(change.getName());
                }
                parts.add("表属性: " + String.join(", ", names));
            }
            List<String> fieldParts = new ArrayList<>();
            for (String name : diff.getColumnsAdded()) {
                fieldParts.add("+" + name);
            }
            for (String name : diff.getColumnsRemoved()) {
                fieldParts.add("-" + name);
            }
            for (TableVersionCompareResponse.ColumnChange change : diff.getColumnsModified()) {
                fieldParts.add("~" + change.getFieldName());
            }
            if (!fieldParts.isEmpty()) {
                parts.add("字段: " + String.join(", ", fieldParts));
            }
            String summary = parts.isEmpty() ? "元数据变更" : String.join("; ", parts);
            return summary.length() > CHANGE_SUMMARY_MAX_LENGTH
                    ? summary.substring(0, CHANGE_SUMMARY_MAX_LENGTH - 3) + "..."
                    : summary;
        } catch (Exception e) {
            log.warn("Failed to build change summary", e);
            return "元数据变更";
        }
    }

    // ---------------- 结构化 diff ----------------

    private JsonNode readSnapshot(String snapshotJson) {
        try {
            return objectMapper.readTree(snapshotJson == null ? "{}" : snapshotJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("快照内容无法解析");
        }
    }

    private void diffTableAttributes(JsonNode leftRoot, JsonNode rightRoot,
                                     TableVersionCompareResponse response) {
        JsonNode leftTable = leftRoot.path("table");
        JsonNode rightTable = rightRoot.path("table");
        Set<String> names = new TreeSet<>();
        leftTable.fieldNames().forEachRemaining(names::add);
        rightTable.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            String leftValue = textValue(leftTable.get(name));
            String rightValue = textValue(rightTable.get(name));
            if (!Objects.equals(leftValue, rightValue)) {
                TableVersionCompareResponse.AttributeChange change = new TableVersionCompareResponse.AttributeChange();
                change.setName(name);
                change.setOldValue(leftValue);
                change.setNewValue(rightValue);
                response.getTableAttributeChanges().add(change);
            }
        }
    }

    private void diffColumns(JsonNode leftRoot, JsonNode rightRoot,
                             TableVersionCompareResponse response) {
        Map<String, JsonNode> leftFields = fieldsByName(leftRoot);
        Map<String, JsonNode> rightFields = fieldsByName(rightRoot);

        Set<String> allNames = new LinkedHashSet<>(leftFields.keySet());
        allNames.addAll(rightFields.keySet());
        for (String name : allNames) {
            JsonNode leftField = leftFields.get(name);
            JsonNode rightField = rightFields.get(name);
            if (leftField == null) {
                response.getColumnsAdded().add(name);
                continue;
            }
            if (rightField == null) {
                response.getColumnsRemoved().add(name);
                continue;
            }
            TableVersionCompareResponse.ColumnChange columnChange = new TableVersionCompareResponse.ColumnChange();
            columnChange.setFieldName(name);
            Set<String> attrNames = new TreeSet<>();
            leftField.fieldNames().forEachRemaining(attrNames::add);
            rightField.fieldNames().forEachRemaining(attrNames::add);
            for (String attrName : attrNames) {
                String leftValue = textValue(leftField.get(attrName));
                String rightValue = textValue(rightField.get(attrName));
                if (!Objects.equals(leftValue, rightValue)) {
                    TableVersionCompareResponse.AttributeChange change = new TableVersionCompareResponse.AttributeChange();
                    change.setName(attrName);
                    change.setOldValue(leftValue);
                    change.setNewValue(rightValue);
                    columnChange.getChanges().add(change);
                }
            }
            if (!columnChange.getChanges().isEmpty()) {
                response.getColumnsModified().add(columnChange);
            }
        }
    }

    private Map<String, JsonNode> fieldsByName(JsonNode root) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        JsonNode fields = root.path("fields");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                String name = textValue(field.get("fieldName"));
                if (name != null) {
                    result.put(name, field);
                }
            }
        }
        return result;
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    // ---------------- 规范化与哈希 ----------------

    private String canonicalizeJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            TreeSet<String> fieldNames = new TreeSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(fieldName).append('"').append(':');
                sb.append(canonicalizeJson(node.get(fieldName)));
            }
            sb.append('}');
            return sb.toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonicalizeJson(node.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return node.toString();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成 hash", e);
        }
    }

    // ---------------- raw diff ----------------

    private String buildUnifiedRawDiff(JsonNode leftRoot, JsonNode rightRoot,
                                       DataTableVersion leftVersion, DataTableVersion rightVersion) {
        String leftText = toPrettyJson(leftRoot);
        String rightText = toPrettyJson(rightRoot);

        List<String> leftLines = Arrays.asList(leftText.split("\\R", -1));
        List<String> rightLines = Arrays.asList(rightText.split("\\R", -1));
        int[][] lcs = buildLcsMatrix(leftLines, rightLines);
        LinkedList<String> diffLines = buildDiffLines(leftLines, rightLines, lcs);

        String leftLabel = leftVersion.getVersionNo() != null ? "v" + leftVersion.getVersionNo() : "unknown";
        String rightLabel = rightVersion.getVersionNo() != null ? "v" + rightVersion.getVersionNo() : "unknown";

        StringBuilder builder = new StringBuilder();
        builder.append("--- ").append(leftLabel).append('\n');
        builder.append("+++ ").append(rightLabel).append('\n');
        builder.append("@@ JSON Snapshot @@").append('\n');
        for (String line : diffLines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private int[][] buildLcsMatrix(List<String> leftLines, List<String> rightLines) {
        int leftSize = leftLines.size();
        int rightSize = rightLines.size();
        int[][] matrix = new int[leftSize + 1][rightSize + 1];
        for (int i = 1; i <= leftSize; i++) {
            for (int j = 1; j <= rightSize; j++) {
                if (Objects.equals(leftLines.get(i - 1), rightLines.get(j - 1))) {
                    matrix[i][j] = matrix[i - 1][j - 1] + 1;
                } else {
                    matrix[i][j] = Math.max(matrix[i - 1][j], matrix[i][j - 1]);
                }
            }
        }
        return matrix;
    }

    private LinkedList<String> buildDiffLines(List<String> leftLines,
                                              List<String> rightLines,
                                              int[][] lcsMatrix) {
        int i = leftLines.size();
        int j = rightLines.size();
        LinkedList<String> diffLines = new LinkedList<>();
        while (i > 0 && j > 0) {
            String leftLine = leftLines.get(i - 1);
            String rightLine = rightLines.get(j - 1);
            if (Objects.equals(leftLine, rightLine)) {
                diffLines.addFirst(" " + leftLine);
                i--;
                j--;
                continue;
            }
            if (lcsMatrix[i - 1][j] >= lcsMatrix[i][j - 1]) {
                diffLines.addFirst("-" + leftLine);
                i--;
            } else {
                diffLines.addFirst("+" + rightLine);
                j--;
            }
        }
        while (i > 0) {
            diffLines.addFirst("-" + leftLines.get(i - 1));
            i--;
        }
        while (j > 0) {
            diffLines.addFirst("+" + rightLines.get(j - 1));
            j--;
        }
        return diffLines;
    }

    private String toPrettyJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ex) {
            return node.toString();
        }
    }
}
