package com.onedata.portal.agentapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.TaskDefinitionRequest;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.service.DataTaskService;
import com.onedata.portal.service.lineage.LineageValidationMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class TaskDefinitionUpdateTest {

    @Mock private DataTaskService dataTaskService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DataTaskService service() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                dataTaskService, "objectMapper", objectMapper);
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(dataTaskService)
                .getTaskOrThrow(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(dataTaskService)
                .createFromDefinition(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(dataTaskService)
                .updateFromDefinition(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        return dataTaskService;
    }

    @Test
    void getTaskReturnsTaskWhenFound() {
        DataTask task = new DataTask();
        task.setId(7L);
        when(dataTaskService.getById(7L)).thenReturn(task);

        assertSame(task, service().getTaskOrThrow(7L));
    }

    @Test
    void getTaskThrowsWhenMissingSoResponseStaysJson() {
        // A null task would otherwise serialize as an empty body, which the
        // portal MCP client rejects as "not valid JSON". The service must throw
        // so the global handler emits a proper JSON error instead.
        when(dataTaskService.getById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service().getTaskOrThrow(999L));
    }

    @Test
    void updateTaskPreservesExistingWorkflowBindingWhenOmitted() {
        DataTask existing = new DataTask();
        existing.setId(7L);
        existing.setWorkflowId(42L);
        when(dataTaskService.getById(7L)).thenReturn(existing);

        Map<String, Object> taskFields = new LinkedHashMap<>();
        taskFields.put("taskName", "renamed-task");
        TaskDefinitionRequest request = new TaskDefinitionRequest();
        request.setTask(taskFields);

        service().updateFromDefinition(7L, request, "agent-operator");

        ArgumentCaptor<DataTask> captor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService)
                .update(captor.capture(), any(), any(), eq(LineageValidationMode.STRICT));
        assertEquals(42L, captor.getValue().getWorkflowId());
    }

    @Test
    void updateTaskHonorsExplicitWorkflowIdWhenProvided() {
        Map<String, Object> taskFields = new LinkedHashMap<>();
        taskFields.put("workflowId", null);
        TaskDefinitionRequest request = new TaskDefinitionRequest();
        request.setTask(taskFields);

        service().updateFromDefinition(7L, request, "agent-operator");

        ArgumentCaptor<DataTask> captor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService)
                .update(captor.capture(), any(), any(), eq(LineageValidationMode.STRICT));
        assertNull(captor.getValue().getWorkflowId());
    }

    @Test
    void updateTaskPassesNullLineageThroughSoExistingLineageIsKept() {
        // 回归：此前 nullSafe() 把 null 转成 emptyList，DataTaskService.update()
        // 会据此把该侧血缘整体删除。null 必须原样下传，才能表达"本次未提供，保留原值"。
        TaskDefinitionRequest request = new TaskDefinitionRequest();
        request.setTask(new LinkedHashMap<>());
        request.setInputTableIds(null);
        request.setOutputTableIds(null);

        service().updateFromDefinition(7L, request, "agent-operator");

        ArgumentCaptor<List<Long>> inputs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Long>> outputs = ArgumentCaptor.forClass(List.class);
        verify(dataTaskService)
                .update(
                        any(),
                        inputs.capture(),
                        outputs.capture(),
                        eq(LineageValidationMode.STRICT));
        assertNull(inputs.getValue());
        assertNull(outputs.getValue());
    }

    @Test
    void updateTaskPassesExplicitEmptyListThroughAsClearIntent() {
        TaskDefinitionRequest request = new TaskDefinitionRequest();
        request.setTask(new LinkedHashMap<>());
        request.setInputTableIds(Collections.emptyList());
        request.setOutputTableIds(null);

        service().updateFromDefinition(7L, request, "agent-operator");

        ArgumentCaptor<List<Long>> inputs = ArgumentCaptor.forClass(List.class);
        verify(dataTaskService)
                .update(any(), inputs.capture(), any(), eq(LineageValidationMode.STRICT));
        assertNotNull(inputs.getValue());
        assertTrue(inputs.getValue().isEmpty());
    }

    @Test
    void updateTaskWithEmptyBodyKeepsBothLineageSidesUntouched() {
        // 完全空的 task body 是合法的"只保留现状"请求，两侧血缘都必须原样保留。
        TaskDefinitionRequest request = new TaskDefinitionRequest();
        request.setTask(new LinkedHashMap<>());

        service().updateFromDefinition(7L, request, "agent-operator");

        ArgumentCaptor<List<Long>> inputs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Long>> outputs = ArgumentCaptor.forClass(List.class);
        verify(dataTaskService)
                .update(
                        any(),
                        inputs.capture(),
                        outputs.capture(),
                        eq(LineageValidationMode.STRICT));
        assertNull(inputs.getValue());
        assertNull(outputs.getValue());
    }

    @Test
    void createTaskUsesStrictValidation() {
        TaskDefinitionRequest request = new TaskDefinitionRequest();
        request.setTask(new LinkedHashMap<>());
        request.setInputTableIds(Collections.singletonList(1L));
        request.setOutputTableIds(Collections.singletonList(2L));

        service().createFromDefinition(request, "agent-operator");

        verify(dataTaskService).create(any(), any(), any(), eq(LineageValidationMode.STRICT));
    }

    @Test
    void taskOwnerKeepsExplicitBusinessOwnershipAndOnlyDefaultsOnCreation() {
        TaskDefinitionRequest explicit = new TaskDefinitionRequest();
        explicit.setTask(Map.of("taskName", "new", "owner", "data-team"));
        service().createFromDefinition(explicit, "7");
        ArgumentCaptor<DataTask> created = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService)
                .create(created.capture(), any(), any(), eq(LineageValidationMode.STRICT));
        assertEquals("data-team", created.getValue().getOwner());
        TaskDefinitionRequest update = new TaskDefinitionRequest();
        update.setTask(Map.of("taskName", "renamed"));
        service().updateFromDefinition(7L, update, "7");
        ArgumentCaptor<DataTask> updated = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService)
                .update(updated.capture(), any(), any(), eq(LineageValidationMode.STRICT));
        assertNull(updated.getValue().getOwner(), "Omitted owner must remain a sparse update");
    }
}
