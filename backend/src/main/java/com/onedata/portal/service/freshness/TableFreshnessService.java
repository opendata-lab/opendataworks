package com.onedata.portal.service.freshness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.TableFreshnessRequest;
import com.onedata.portal.dto.TableFreshnessResponse;
import com.onedata.portal.dto.WorkflowFreshnessResponse;
import com.onedata.portal.dto.WorkflowTableRelationItem;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.entity.TableFreshnessResult;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableFreshnessConfigMapper;
import com.onedata.portal.mapper.TableFreshnessResultMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表级新鲜度契约的读写与按需检查。承载保存校验（字段白名单、SQL 形状、模式必填、互斥），
 * 控制器只做绑定与响应映射。
 */
@Service
@RequiredArgsConstructor
public class TableFreshnessService {

    private static final int MAX_FILTER_LENGTH = 512;
    private static final int MAX_QUERY_LENGTH = 2048;

    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final TableFreshnessConfigMapper freshnessConfigMapper;
    private final TableFreshnessResultMapper freshnessResultMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final FreshnessContractResolver contractResolver;
    private final FreshnessCheckService freshnessCheckService;

    /**
     * 查询生效契约（含字段来源）与最近一次结果。
     */
    public TableFreshnessResponse getFreshness(Long tableId) {
        DataTable table = requireTable(tableId);
        TableFreshnessConfig config = findConfig(tableId);

        TableFreshnessResponse response = new TableFreshnessResponse();
        response.setTableId(tableId);
        response.setConfigured(config != null);
        response.setConfig(config);

        contractResolver.resolve(table, config)
            .ifPresent(contract -> response.setEffective(toEffective(contract)));

        response.setLatestResult(findLatestResult(tableId));
        return response;
    }

    /**
     * upsert 表级契约。校验字段白名单、SQL 形状、模式必填与互斥。
     */
    public void saveFreshness(Long tableId, TableFreshnessRequest request, String operator) {
        DataTable table = requireTable(tableId);
        validate(table, request);

        TableFreshnessConfig config = findConfig(tableId);
        boolean isNew = config == null;
        if (isNew) {
            config = new TableFreshnessConfig();
            config.setTableId(tableId);
            config.setCreatedBy(operator);
        }
        config.setMode(request.getMode().trim().toLowerCase());
        config.setLoadedAtField(trimToNull(request.getLoadedAtField()));
        config.setLoadedAtQuery(trimToNull(request.getLoadedAtQuery()));
        config.setPartitionFormat(trimToNull(request.getPartitionFormat()));
        config.setFilterExpr(trimToNull(request.getFilterExpr()));
        config.setWarnAfterCount(request.getWarnAfterCount());
        config.setWarnAfterPeriod(normalizePeriod(request.getWarnAfterPeriod()));
        config.setErrorAfterCount(request.getErrorAfterCount());
        config.setErrorAfterPeriod(normalizePeriod(request.getErrorAfterPeriod()));
        config.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
        config.setUpdatedBy(operator);

        if (isNew) {
            freshnessConfigMapper.insert(config);
        } else {
            freshnessConfigMapper.updateById(config);
        }
    }

    public void deleteFreshness(Long tableId) {
        TableFreshnessConfig config = findConfig(tableId);
        if (config != null) {
            freshnessConfigMapper.deleteById(config.getId());
        }
    }

    /**
     * 按需检查单表。无可用契约时抛出。
     */
    public FreshnessCheckResult checkNow(Long tableId, String operator) {
        DataTable table = requireTable(tableId);
        TableFreshnessConfig config = findConfig(tableId);
        FreshnessContract contract = contractResolver.resolve(table, config)
            .orElseThrow(() -> new IllegalArgumentException("该表未配置可用的新鲜度契约，请先设置时间字段与时效阈值"));
        return freshnessCheckService.check(table, contract, "manual", operator == null ? "manual" : operator);
    }

    public List<TableFreshnessResult> history(Long tableId, int limit) {
        return freshnessResultMapper.selectList(new LambdaQueryWrapper<TableFreshnessResult>()
            .eq(TableFreshnessResult::getTableId, tableId)
            .orderByDesc(TableFreshnessResult::getCreatedAt)
            .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 工作流详情页「数据新鲜度」页签：该工作流关联表（读取表与写出表）的最新状态汇总、每次运行的问题表数、逐表最新结果。
     */
    public WorkflowFreshnessResponse workflowFreshness(Long workflowId) {
        WorkflowFreshnessResponse response = new WorkflowFreshnessResponse();
        response.setWorkflowId(workflowId);
        WorkflowFreshnessResponse.Summary summary = new WorkflowFreshnessResponse.Summary();
        response.setSummary(summary);
        response.setRuns(new ArrayList<>());
        response.setTables(new ArrayList<>());

        List<WorkflowTableRelationItem> relations = tableTaskRelationMapper.selectWorkflowTableRelations(workflowId);
        if (relations == null || relations.isEmpty()) {
            return response;
        }

        Map<Long, Set<String>> typesByTable = new java.util.LinkedHashMap<>();
        for (WorkflowTableRelationItem rel : relations) {
            if (rel.getTableId() != null && rel.getRelationType() != null) {
                typesByTable.computeIfAbsent(rel.getTableId(), k -> new java.util.HashSet<>())
                    .add(rel.getRelationType().trim().toLowerCase());
            }
        }
        if (typesByTable.isEmpty()) {
            return response;
        }

        List<DataTable> tables = dataTableMapper.selectBatchIds(typesByTable.keySet()).stream()
            .filter(t -> !Integer.valueOf(1).equals(t.getDeleted()))
            .collect(Collectors.toList());
        if (tables.isEmpty()) {
            return response;
        }

        // 排序规则：write/both 优先，其后为 read；同类按表名排序
        tables.sort((a, b) -> {
            Set<String> typesA = typesByTable.get(a.getId());
            Set<String> typesB = typesByTable.get(b.getId());
            boolean writeA = typesA != null && typesA.contains("write");
            boolean writeB = typesB != null && typesB.contains("write");
            if (writeA != writeB) {
                return writeA ? -1 : 1;
            }
            String nameA = a.getTableName() == null ? "" : a.getTableName();
            String nameB = b.getTableName() == null ? "" : b.getTableName();
            return nameA.compareToIgnoreCase(nameB);
        });

        List<Long> activeIds = tables.stream().map(DataTable::getId).collect(Collectors.toList());
        summary.setTotal(tables.size());

        // 逐表最新结果（按 tableId 取最近一条）
        Map<Long, TableFreshnessResult> latestByTable = new java.util.HashMap<>();
        for (TableFreshnessResult r : freshnessResultMapper.selectList(
                new LambdaQueryWrapper<TableFreshnessResult>()
                    .in(TableFreshnessResult::getTableId, activeIds)
                    .orderByDesc(TableFreshnessResult::getCreatedAt))) {
            latestByTable.putIfAbsent(r.getTableId(), r);
        }

        for (DataTable table : tables) {
            Set<String> types = typesByTable.get(table.getId());
            boolean hasRead = types != null && types.contains("read");
            boolean hasWrite = types != null && types.contains("write");
            String relationType = hasRead && hasWrite ? "both" : (hasRead ? "read" : "write");

            if (hasWrite) {
                summary.setWriteCount(summary.getWriteCount() + 1);
            }
            if (hasRead) {
                summary.setReadCount(summary.getReadCount() + 1);
            }

            TableFreshnessResult latest = latestByTable.get(table.getId());
            WorkflowFreshnessResponse.TableStatus ts = new WorkflowFreshnessResponse.TableStatus();
            ts.setTableId(table.getId());
            ts.setDbName(table.getDbName());
            ts.setTableName(table.getTableName());
            ts.setRelationType(relationType);
            ts.setConfigured(findConfig(table.getId()) != null);
            if (latest != null) {
                ts.setStatus(latest.getStatus());
                ts.setMaxLoadedAt(latest.getMaxLoadedAt());
                ts.setAgeSeconds(latest.getAgeSeconds());
                ts.setCheckedAt(latest.getCreatedAt());
                bumpSummary(summary, latest.getStatus());
            } else {
                summary.setUnconfigured(summary.getUnconfigured() + 1);
            }
            response.getTables().add(ts);
        }

        response.setRuns(buildRuns(activeIds));
        return response;
    }

    private void bumpSummary(WorkflowFreshnessResponse.Summary s, String status) {
        switch (status == null ? "" : status) {
            case FreshnessCheckResult.STATUS_PASS: s.setPass(s.getPass() + 1); break;
            case FreshnessCheckResult.STATUS_WARN: s.setWarn(s.getWarn() + 1); break;
            case FreshnessCheckResult.STATUS_ERROR: s.setError(s.getError() + 1); break;
            case FreshnessCheckResult.STATUS_RUNTIME_ERROR: s.setRuntimeError(s.getRuntimeError() + 1); break;
            default: break;
        }
    }

    /**
     * 按触发实例聚合成「每次运行」，返回每次运行的问题表数。近 500 行内、最近 20 次运行。
     */
    private List<WorkflowFreshnessResponse.Run> buildRuns(List<Long> tableIds) {
        List<TableFreshnessResult> rows = freshnessResultMapper.selectList(
            new LambdaQueryWrapper<TableFreshnessResult>()
                .in(TableFreshnessResult::getTableId, tableIds)
                .isNotNull(TableFreshnessResult::getWorkflowInstanceId)
                .orderByDesc(TableFreshnessResult::getCreatedAt)
                .last("LIMIT 500"));

        // 保序（createdAt 倒序）分组
        Map<Long, List<TableFreshnessResult>> byInstance = rows.stream()
            .collect(Collectors.groupingBy(TableFreshnessResult::getWorkflowInstanceId,
                java.util.LinkedHashMap::new, Collectors.toList()));

        List<WorkflowFreshnessResponse.Run> runs = new ArrayList<>();
        for (Map.Entry<Long, List<TableFreshnessResult>> e : byInstance.entrySet()) {
            List<TableFreshnessResult> group = e.getValue();
            WorkflowFreshnessResponse.Run run = new WorkflowFreshnessResponse.Run();
            run.setWorkflowInstanceId(e.getKey());
            run.setCheckedAt(group.get(0).getCreatedAt());
            run.setTotal(group.size());
            run.setProblem((int) group.stream()
                .filter(r -> !FreshnessCheckResult.STATUS_PASS.equals(r.getStatus()))
                .count());
            runs.add(run);
            if (runs.size() >= 20) {
                break;
            }
        }
        return runs;
    }

    // ---- 校验 ----

    private void validate(DataTable table, TableFreshnessRequest request) {
        FreshnessMode mode = FreshnessMode.parse(request.getMode())
            .orElseThrow(() -> new IllegalArgumentException("非法的取值模式: " + request.getMode()));

        String loadedAtField = trimToNull(request.getLoadedAtField());
        String loadedAtQuery = trimToNull(request.getLoadedAtQuery());
        if (loadedAtField != null && loadedAtQuery != null) {
            throw new IllegalArgumentException("loadedAtField 与 loadedAtQuery 互斥，只能配置其一");
        }

        switch (mode) {
            case COLUMN:
                if (loadedAtField == null) {
                    throw new IllegalArgumentException("column 模式必须指定加载时间列");
                }
                break;
            case CUSTOM_SQL:
                if (loadedAtQuery == null) {
                    throw new IllegalArgumentException("custom_sql 模式必须指定自定义查询");
                }
                validateQuery(loadedAtQuery);
                break;
            case PARTITION:
                if (trimToNull(request.getPartitionFormat()) == null) {
                    throw new IllegalArgumentException("partition 模式必须指定分区日期格式");
                }
                requirePartitionColumn(table.getId());
                break;
            case METADATA:
            default:
                break;
        }

        if (loadedAtField != null) {
            requireRealColumn(table.getId(), loadedAtField);
        }
        validateFilter(request.getFilterExpr());
        validateThreshold(request.getWarnAfterCount(), request.getWarnAfterPeriod(), "warn");
        validateThreshold(request.getErrorAfterCount(), request.getErrorAfterPeriod(), "error");
    }

    private void requireRealColumn(Long tableId, String column) {
        List<DataField> fields = dataFieldMapper.selectList(
            new LambdaQueryWrapper<DataField>().eq(DataField::getTableId, tableId));
        boolean exists = fields.stream()
            .anyMatch(f -> f.getFieldName() != null && f.getFieldName().equalsIgnoreCase(column));
        if (!exists) {
            throw new IllegalArgumentException("加载时间列不存在于该表: " + column);
        }
    }

    private void requirePartitionColumn(Long tableId) {
        List<DataField> fields = dataFieldMapper.selectList(
            new LambdaQueryWrapper<DataField>().eq(DataField::getTableId, tableId));
        boolean hasPartition = fields.stream()
            .anyMatch(f -> f.getIsPartition() != null && f.getIsPartition() == 1);
        if (!hasPartition) {
            throw new IllegalArgumentException("该表没有分区列，无法使用 partition 模式");
        }
    }

    private void validateQuery(String query) {
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("自定义查询过长（上限 " + MAX_QUERY_LENGTH + "）");
        }
        String upper = query.trim().toUpperCase();
        if (!upper.startsWith("SELECT")) {
            throw new IllegalArgumentException("自定义查询必须以 SELECT 开头");
        }
        rejectDangerousSql(query, "自定义查询");
    }

    private void validateFilter(String filter) {
        String trimmed = trimToNull(filter);
        if (trimmed == null) {
            return;
        }
        if (trimmed.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException("过滤条件过长（上限 " + MAX_FILTER_LENGTH + "）");
        }
        rejectDangerousSql(trimmed, "过滤条件");
    }

    private void rejectDangerousSql(String sql, String label) {
        if (sql.indexOf(';') >= 0 || sql.contains("--") || sql.contains("/*")) {
            throw new IllegalArgumentException(label + "不能包含分号或注释符");
        }
    }

    private void validateThreshold(Integer count, String period, String label) {
        if (count == null && period == null) {
            return;
        }
        if (count == null || count <= 0) {
            throw new IllegalArgumentException(label + " 阈值数量必须为正整数");
        }
        if (!FreshnessPeriod.parse(period).isPresent()) {
            throw new IllegalArgumentException(label + " 阈值单位非法，仅支持 minute/hour/day");
        }
    }

    // ---- 辅助 ----

    private TableFreshnessResponse.EffectiveContract toEffective(FreshnessContract contract) {
        TableFreshnessResponse.EffectiveContract dto = new TableFreshnessResponse.EffectiveContract();
        dto.setMode(contract.getMode() == null ? null : contract.getMode().code());
        dto.setLoadedAtField(contract.getLoadedAtField());
        dto.setLoadedAtQuery(contract.getLoadedAtQuery());
        dto.setPartitionFormat(contract.getPartitionFormat());
        dto.setFilterExpr(contract.getFilterExpr());
        if (contract.getWarnAfter() != null) {
            dto.setWarnAfter(new TableFreshnessResponse.Threshold(
                contract.getWarnAfter().getCount(), contract.getWarnAfter().getPeriod().code()));
        }
        if (contract.getErrorAfter() != null) {
            dto.setErrorAfter(new TableFreshnessResponse.Threshold(
                contract.getErrorAfter().getCount(), contract.getErrorAfter().getPeriod().code()));
        }
        Map<String, String> sources = contract.getFieldSources().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().name().toLowerCase(), (a, b) -> a, java.util.LinkedHashMap::new));
        dto.setFieldSources(sources);
        return dto;
    }

    private DataTable requireTable(Long tableId) {
        DataTable table = dataTableMapper.selectById(tableId);
        if (table == null) {
            throw new IllegalArgumentException("表不存在: " + tableId);
        }
        return table;
    }

    private TableFreshnessConfig findConfig(Long tableId) {
        return freshnessConfigMapper.selectOne(new LambdaQueryWrapper<TableFreshnessConfig>()
            .eq(TableFreshnessConfig::getTableId, tableId).last("LIMIT 1"));
    }

    private TableFreshnessResult findLatestResult(Long tableId) {
        List<TableFreshnessResult> latest = freshnessResultMapper.selectList(
            new LambdaQueryWrapper<TableFreshnessResult>()
                .eq(TableFreshnessResult::getTableId, tableId)
                .orderByDesc(TableFreshnessResult::getCreatedAt)
                .last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.get(0);
    }

    public static String currentOperator() {
        String userId = UserContextHolder.getCurrentUserId();
        return StringUtils.hasText(userId) ? userId : "system";
    }

    private String normalizePeriod(String period) {
        return FreshnessPeriod.parse(period).map(FreshnessPeriod::code).orElse(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
