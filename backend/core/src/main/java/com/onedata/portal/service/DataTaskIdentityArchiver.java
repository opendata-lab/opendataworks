package com.onedata.portal.service;

import com.onedata.portal.entity.DataTask;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.mapper.DataTaskMapper;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;



/**
 * 任务唯一键归档器。
 *
 * <p>{@code data_task} 使用逻辑删除，但 {@code uk_task_code(task_code)} 与
 * {@code uk_task_name(task_name, deleted)} 会继续约束已删除记录。任何软删除路径
 * 都必须先归档唯一字段，否则同名任务重复创建、删除时会触发 duplicate key。</p>
 *
 * <p>软删除路径不止一条：单任务删除走 {@link DataTaskService#delete(Long)}，
 * 工作流级联删除走 {@code WorkflowCommandService.deleteWorkflow}。归档规则集中在这里，
 * 避免各调用方各自实现后缀与截断规则。{@code WorkflowCommandService} 不能直接依赖
 * {@code DataTaskService}（会形成 {@code DataTaskService -> WorkflowService ->
 * WorkflowCommandService} 的循环依赖），因此归档器独立成组件，只依赖 {@link DataTaskMapper}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataTaskIdentityArchiver {

    // Keep aligned with data_task.task_name VARCHAR(100) in V1__init.sql.
    private static final int MAX_TASK_NAME_LENGTH = 100;
    // Keep aligned with data_task.task_code VARCHAR(100) in V1__init.sql.
    private static final int MAX_TASK_CODE_LENGTH = 100;
    private static final int MAX_ARCHIVED_CODE_ATTEMPTS = 1000;
    private static final String DELETED_IDENTITY_SUFFIX = "__deleted_";

    private final DataTaskMapper dataTaskMapper;

    /**
     * 逻辑删除前释放任务名称和编码的唯一键。
     *
     * <p>归档后缀包含任务 ID，因此同名任务反复创建、删除时不会在
     * {@code uk_task_name(task_name, deleted)} 上发生冲突，也不会继续占用
     * {@code uk_task_code(task_code)}。</p>
     *
     * @return 归档是否成功；返回 {@code false} 表示记录的删除状态已被并发修改
     */
    public boolean archive(DataTask task) {
        if (task == null || task.getId() == null) {
            return false;
        }
        String originalTaskCode = task.getTaskCode();
        String originalTaskName = task.getTaskName();
        String suffix = DELETED_IDENTITY_SUFFIX + task.getId();
        String archivedTaskCode = buildAvailableArchivedTaskCode(originalTaskCode, task.getId());
        String archivedTaskName = buildArchivedIdentity(
                originalTaskName, suffix, MAX_TASK_NAME_LENGTH);
        int expectedDeleted = Objects.equals(task.getDeleted(), 1) ? 1 : 0;
        int updated = dataTaskMapper.archiveUniqueIdentity(
                task.getId(), archivedTaskCode, archivedTaskName, expectedDeleted);
        if (updated == 0) {
            return false;
        }
        log.info("Archived task identity: taskId={}, taskCode={} -> {}, taskName={} -> {}",
                task.getId(), originalTaskCode, archivedTaskCode, originalTaskName, archivedTaskName);
        task.setTaskCode(archivedTaskCode);
        task.setTaskName(archivedTaskName);
        return true;
    }

    /**
     * 批量逻辑删除前释放任务的名称和编码唯一键，用于工作流级联删除。
     *
     * <p>按 ID 读取的都是未删除记录，已被并发删除的任务会被跳过。</p>
     */
    public void archiveByIds(Collection<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return;
        }
        List<DataTask> tasks = dataTaskMapper.selectBatchIds(taskIds);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (DataTask task : tasks) {
            if (!archive(task)) {
                log.info("Task was already deleted while archiving identity: taskId={}", task.getId());
            }
        }
    }

    /**
     * 单层兼容修复：仅当创建请求命中修复前遗留的已删除编码时归档该记录。
     *
     * <p>工作流导入/同步仍保留其“包含删除记录分配新编码”的既有策略；
     * 普通任务创建则复用用户明确提交的编码，避免全量数据迁移。</p>
     */
    public void releaseDeletedTaskCode(String taskCode) {
        if (!StringUtils.hasText(taskCode)) {
            return;
        }
        DataTask deletedTask = dataTaskMapper.selectDeletedByTaskCode(taskCode);
        if (deletedTask != null && !archive(deletedTask)) {
            throw new BusinessException("任务编码已存在: " + taskCode);
        }
    }

    private String buildAvailableArchivedTaskCode(String original, Long taskId) {
        for (int attempt = 1; attempt <= MAX_ARCHIVED_CODE_ATTEMPTS; attempt++) {
            String suffix = DELETED_IDENTITY_SUFFIX + taskId
                    + (attempt == 1 ? "" : "_" + attempt);
            String candidate = buildArchivedIdentity(original, suffix, MAX_TASK_CODE_LENGTH);
            Long count = dataTaskMapper.countByTaskCodeIncludingDeleted(candidate);
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法生成唯一的任务归档编码: taskId=" + taskId);
    }

    private String buildArchivedIdentity(String original, String suffix, int maxLength) {
        int maxPrefixLength = Math.max(0, maxLength - suffix.length());
        String prefix = StringUtils.hasText(original) ? original : "task";
        int codePointCount = prefix.codePointCount(0, prefix.length());
        if (codePointCount > maxPrefixLength) {
            int endIndex = prefix.offsetByCodePoints(0, maxPrefixLength);
            prefix = prefix.substring(0, endIndex);
        }
        return prefix + suffix;
    }
}
