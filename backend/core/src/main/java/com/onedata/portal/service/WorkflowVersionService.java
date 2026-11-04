package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;



/**
 * 工作流版本服务
 */
@Service
@RequiredArgsConstructor
public class WorkflowVersionService {

    private static final int SNAPSHOT_SCHEMA_VERSION_DEFINITION = 3;

    private final WorkflowVersionMapper workflowVersionMapper;

    /**
     * 用最终状态重写一份已存在的版本快照。
     *
     * <p>只服务于导入：工作流必须先由 {@code createWorkflow} 建出来才能拿到 id，而初始版本快照
     * 就在那一步生成，此时运行态归属（workflowCode / publishStatus / 调度标识）尚未写入。
     * 与其额外产生一个"错的初始版本 + 对的第二版本"，不如把这唯一一版就地改成最终状态，
     * 否则回滚到初始版本会把发布状态和调度恢复错，甚至让下一次发布被误判为首次部署。
     */
    @Transactional
    public WorkflowVersion replaceSnapshot(Long versionId, String snapshot) {
        if (versionId == null || !StringUtils.hasText(snapshot)) {
            return null;
        }
        WorkflowVersion version = workflowVersionMapper.selectById(versionId);
        if (version == null) {
            return null;
        }
        version.setStructureSnapshot(snapshot);
        version.setSnapshotSchemaVersion(SNAPSHOT_SCHEMA_VERSION_DEFINITION);
        workflowVersionMapper.updateById(version);
        return version;
    }

    @Transactional
    public WorkflowVersion createVersion(Long workflowId,
                                         String snapshot,
                                         String changeSummary,
                                         String triggerSource,
                                         String operator) {
        return createVersion(workflowId,
                snapshot,
                changeSummary,
                triggerSource,
                operator,
                SNAPSHOT_SCHEMA_VERSION_DEFINITION,
                null);
    }

    @Transactional
    public WorkflowVersion createVersion(Long workflowId,
                                         String snapshot,
                                         String changeSummary,
                                         String triggerSource,
                                         String operator,
                                         Integer snapshotSchemaVersion,
                                         Long rollbackFromVersionId) {
        WorkflowVersion latest = workflowVersionMapper.selectOne(
            Wrappers.<WorkflowVersion>lambdaQuery()
                .eq(WorkflowVersion::getWorkflowId, workflowId)
                .orderByDesc(WorkflowVersion::getVersionNo)
                .last("limit 1")
        );
        int nextVersion = latest == null ? 1 : latest.getVersionNo() + 1;

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowId(workflowId);
        version.setVersionNo(nextVersion);
        version.setStructureSnapshot(snapshot);
        version.setChangeSummary(changeSummary);
        version.setTriggerSource(triggerSource);
        version.setCreatedBy(operator);
        version.setSnapshotSchemaVersion(snapshotSchemaVersion);
        version.setRollbackFromVersionId(rollbackFromVersionId);
        workflowVersionMapper.insert(version);
        return version;
    }

    public List<WorkflowVersion> listByWorkflow(Long workflowId) {
        return workflowVersionMapper.selectList(
            Wrappers.<WorkflowVersion>lambdaQuery()
                .eq(WorkflowVersion::getWorkflowId, workflowId)
                .orderByDesc(WorkflowVersion::getVersionNo)
        );
    }

    public WorkflowVersion getById(Long id) {
        return workflowVersionMapper.selectById(id);
    }

    @Transactional
    public void deleteByWorkflowId(Long workflowId) {
        if (workflowId == null) {
            return;
        }
        workflowVersionMapper.delete(
                Wrappers.<WorkflowVersion>lambdaQuery()
                        .eq(WorkflowVersion::getWorkflowId, workflowId));
    }
}
