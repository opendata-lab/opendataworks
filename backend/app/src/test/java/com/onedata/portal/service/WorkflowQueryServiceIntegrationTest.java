package com.onedata.portal.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.mapper.WorkflowInstanceCacheMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "workflow.runtime-sync.enabled=false"
})
@DisplayName("工作流查询服务集成测试")
class WorkflowQueryServiceIntegrationTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowQueryService workflowQueryService;

    @Autowired
    private WorkflowInstanceCacheMapper workflowInstanceCacheMapper;

    @Test
    @DisplayName("列表和详情应读取真实持久化版本与实例缓存")
    void listAndDetailShouldReadPersistedVersionAndInstanceCache() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String workflowName = "it_query_wf_" + suffix;

        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName(workflowName);
        request.setDescription("query-service-it");
        request.setOperator("it-query");
        request.setTasks(Collections.emptyList());

        DataWorkflow workflow = workflowService.createWorkflow(request);
        assertNotNull(workflow.getId());
        assertNotNull(workflow.getCurrentVersionId());

        WorkflowInstanceCache cache = new WorkflowInstanceCache();
        cache.setWorkflowId(workflow.getId());
        cache.setInstanceId(91001L);
        cache.setState("SUCCESS");
        cache.setTriggerType("MANUAL");
        cache.setDurationMs(1200L);
        cache.setStartTime(new Date(1710000000000L));
        cache.setEndTime(new Date(1710003600000L));
        workflowInstanceCacheMapper.insert(cache);
        assertNotNull(workflowInstanceCacheMapper.selectById(cache.getId()).getCreatedAt());

        WorkflowQueryRequest query = new WorkflowQueryRequest();
        query.setKeyword(workflowName);
        query.setStatus("draft");
        query.setPageNum(1);
        query.setPageSize(10);

        Page<DataWorkflow> page = workflowQueryService.list(query);
        assertFalse(page.getRecords().isEmpty());
        DataWorkflow listed = page.getRecords().stream()
                .filter(item -> workflow.getId().equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("created workflow should be listed"));
        assertEquals(Integer.valueOf(1), listed.getCurrentVersionNo());
        assertEquals(Long.valueOf(91001L), listed.getLatestInstanceId());
        assertEquals("SUCCESS", listed.getLatestInstanceState());
        assertNotNull(listed.getLatestInstanceStartTime());

        WorkflowDetailResponse detail = workflowQueryService.getDetail(workflow.getId());
        assertEquals(workflow.getId(), detail.getWorkflow().getId());
        assertEquals(Integer.valueOf(1), detail.getWorkflow().getCurrentVersionNo());
        assertFalse(detail.getVersions().isEmpty());
        assertEquals(1, detail.getRecentInstances().size());
        assertEquals(Long.valueOf(91001L), detail.getRecentInstances().get(0).getInstanceId());
    }
}
