package com.onedata.portal.service;

import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.lineage.LineageValidationMode;
import com.onedata.portal.service.lineage.TaskLineageConsistencyChecker;
import com.onedata.portal.service.lineage.TaskLineageWriteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class DataTaskServiceValidationTest {

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private TaskExecutionLogMapper executionLogMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataQueryService dataQueryService;

    @Mock
    private DorisClusterService dorisClusterService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private TaskLineageWriteService taskLineageWriteService;

    @Mock
    private TaskLineageConsistencyChecker taskLineageConsistencyChecker;

    @InjectMocks
    private DataTaskService dataTaskService;

    @Test
    void validateTaskShouldAllowSqlWithoutInput() {
        DataTask task = sqlTask();

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(dataTaskService,
                "validateTask",
                task,
                Collections.emptyList(),
                Collections.singletonList(100L),
                LineageValidationMode.LENIENT));
    }

    @Test
    void validateTaskShouldRejectSqlWithoutOutput() {
        DataTask task = sqlTask();

        assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(dataTaskService,
                        "validateTask",
                        task,
                        Collections.singletonList(100L),
                        Collections.emptyList(),
                        LineageValidationMode.LENIENT));
    }

    @Test
    void validateTaskShouldAllowSqlDatasourceToResolveDuringPublish() {
        DataTask task = sqlTask();
        task.setDatasourceName(null);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(dataTaskService,
                "validateTask",
                task,
                Collections.singletonList(100L),
                Collections.singletonList(200L),
                LineageValidationMode.LENIENT));
    }

    @Test
    void validatePublishMetadataShouldRejectSqlWithoutDatasource() {
        DataTask task = sqlTask();
        task.setId(1L);
        task.setTaskName("sql_without_datasource");
        task.setDatasourceName(null);
        task.setDolphinTaskCode(1001L);
        task.setDolphinTaskVersion(1);
        task.setPriority(5);
        task.setRetryTimes(1);
        task.setRetryInterval(60);
        task.setTimeoutSeconds(600);

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(dataTaskService,
                        "validatePublishMetadata",
                        Collections.singletonList(task)));
    }

    @Test
    void validateTaskShouldRejectShellWithoutOutput() {
        DataTask task = new DataTask();
        task.setDolphinNodeType("SHELL");

        assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(dataTaskService,
                        "validateTask",
                        task,
                        Collections.singletonList(100L),
                        Collections.emptyList(),
                        LineageValidationMode.LENIENT));
    }

    @Test
    void validateTaskShouldRejectDataxWithoutOutput() {
        DataTask task = new DataTask();
        task.setDolphinNodeType("DATAX");

        assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(dataTaskService,
                        "validateTask",
                        task,
                        Collections.singletonList(100L),
                        Collections.emptyList(),
                        LineageValidationMode.LENIENT));
    }

    @Test
    void validateTaskShouldPassWhenSqlHasInputAndOutput() {
        DataTask task = sqlTask();

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(dataTaskService,
                "validateTask",
                task,
                Collections.singletonList(100L),
                Collections.singletonList(200L),
                LineageValidationMode.LENIENT));
    }

    private DataTask sqlTask() {
        DataTask task = new DataTask();
        task.setDolphinNodeType("SQL");
        task.setDatasourceName("doris_sql_ds");
        return task;
    }
}
