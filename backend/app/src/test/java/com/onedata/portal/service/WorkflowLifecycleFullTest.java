package com.onedata.portal.service;

import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流完整生命周期集成测试（合并版）
 *
 * 所有步骤在一个测试方法中完成，确保状态一致性
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("工作流生命周期集成测试")
class WorkflowLifecycleFullTest {

    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final long TEST_WORKFLOW_CODE = 900000L;
    private static final String HIDDEN_ENGINE = "dinky";

    @Autowired
    private DataTaskService dataTaskService;

    @SpyBean
    private DolphinSchedulerService dolphinSchedulerService;

    @Autowired
    private DataTaskMapper dataTaskMapper;

    @Autowired
    private DataTableMapper dataTableMapper;

    @Autowired
    private DataLineageMapper dataLineageMapper;

    private final List<Long> hiddenDolphinTaskIds = new ArrayList<>();

    @BeforeAll
    static void setup() {
        System.out.println("\n" + createSep(80));
        System.out.println("🚀 工作流生命周期集成测试");
        System.out.println(createSep(80) + "\n");
    }

    @BeforeEach
    void isolateDolphinTasksAndMockScheduler() {
        hideExternalDolphinTasks();
        configureDolphinSchedulerMock();
    }

    @AfterEach
    void restoreExternalDolphinTasks() {
        for (Long taskId : hiddenDolphinTaskIds) {
            DataTask task = dataTaskMapper.selectById(taskId);
            if (task != null) {
                task.setEngine("dolphin");
                dataTaskMapper.updateById(task);
            }
        }
        hiddenDolphinTaskIds.clear();
    }

    @Test
    @DisplayName("完整工作流生命周期测试")
    void testCompleteWorkflowLifecycle() {
        // 清理旧数据
        cleanupOldTestData();

        // 步骤1：创建3个表
        System.out.println("\n[步骤1] 创建3个测试表\n");
        String tableAName = "test_table_a_" + RUN_ID;
        String tableBName = "test_table_b_" + RUN_ID;
        String tableCName = "test_table_c_" + RUN_ID;
        String tableVerifyName = "test_table_verify_" + RUN_ID;
        String tableAnalyzeName = "test_table_analyze_" + RUN_ID;
        Long tableAId = createTable(tableAName, "ods", "源表A");
        Long tableBId = createTable(tableBName, "dwd", "中间表B");
        Long tableCId = createTable(tableCName, "dws", "目标表C");
        Long tableVerifyId = createTable(tableVerifyName, "ads", "验证结果表");
        Long tableAnalyzeId = createTable(tableAnalyzeName, "ads", "分析结果表");
        System.out.println("✅ 5个表创建成功\n");

        // 步骤2：创建3个串行任务
        System.out.println("\n[步骤2] 创建3个串行依赖的SQL任务\n");
        Long task1Id = createTask("test_task_1_a_to_b_" + RUN_ID, "转换任务1_" + RUN_ID,
            "INSERT INTO " + tableBName + " SELECT * FROM " + tableAName,
            Arrays.asList(tableAId), Arrays.asList(tableBId));

        Long task2Id = createTask("test_task_2_b_to_c_" + RUN_ID, "转换任务2_" + RUN_ID,
            "INSERT INTO " + tableCName + " SELECT * FROM " + tableBName,
            Arrays.asList(tableBId), Arrays.asList(tableCId));

        Long task3Id = createTask("test_task_3_verify_c_" + RUN_ID, "验证任务3_" + RUN_ID,
            "INSERT INTO " + tableVerifyName + " SELECT COUNT(*) FROM " + tableCName,
            Arrays.asList(tableCId), Arrays.asList(tableVerifyId));

        System.out.println("✅ 3个任务创建成功");
        System.out.println("   依赖关系: task_1 -> task_2 -> task_3\n");

        // 步骤3：发布工作流并上线
        System.out.println("\n[步骤3] 发布工作流并上线\n");
        dataTaskService.publish(task1Id);
        dataTaskService.publish(task2Id);
        dataTaskService.publish(task3Id);

        DataTask task1 = dataTaskMapper.selectById(task1Id);
        assertNotNull(task1.getDolphinProcessCode(), "工作流代码应已生成");
        Long workflowCode = task1.getDolphinProcessCode();

        System.out.println("✅ 工作流已创建并上线");
        System.out.println("   工作流代码: " + workflowCode);
        System.out.println("   状态: ONLINE\n");

        // 步骤4：下线工作流
        System.out.println("\n[步骤4] 下线工作流\n");
        dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
        System.out.println("✅ 工作流已下线\n");

        // 步骤5：添加新任务
        System.out.println("\n[步骤5] 添加新任务 task_4\n");
        Long task4Id = createTask("test_task_4_analyze_b_" + RUN_ID, "分析任务4_" + RUN_ID,
            "INSERT INTO " + tableAnalyzeName + " SELECT AVG(value) FROM " + tableBName,
            Arrays.asList(tableBId), Arrays.asList(tableAnalyzeId));

        System.out.println("✅ 任务4创建成功");
        System.out.println("   依赖关系: task_1 -> task_2 -> task_3");
        System.out.println("                   \\-> task_4\n");

        // 步骤6：重新发布并上线
        System.out.println("\n[步骤6] 重新发布工作流\n");
        dataTaskService.publish(task4Id);

        DataTask task4 = dataTaskMapper.selectById(task4Id);
        assertNotNull(task4.getDolphinProcessCode());
        assertEquals(workflowCode, task4.getDolphinProcessCode(), "新任务应属于同一工作流");

        System.out.println("✅ 工作流重新上线");
        System.out.println("   任务数量: 4\n");

        // 步骤7：验证血缘关系
        System.out.println("\n[步骤7] 验证血缘关系\n");
        verifyLineage(task1Id, tableAId, tableBId, "table_a -> task_1 -> table_b");
        verifyLineage(task2Id, tableBId, tableCId, "table_b -> task_2 -> table_c");
        verifyLineage(task3Id, tableCId, tableVerifyId, "table_c -> task_3 -> verify");
        verifyLineage(task4Id, tableBId, tableAnalyzeId, "table_b -> task_4 -> analyze");

        System.out.println("✅ 所有血缘关系验证通过\n");

        // 步骤8：清理
        System.out.println("\n[步骤8] 清理测试数据\n");
        dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
        dataTaskService.delete(task1Id);
        dataTaskService.delete(task2Id);
        dataTaskService.delete(task3Id);
        dataTaskService.delete(task4Id);
        dataTableMapper.deleteById(tableAId);
        dataTableMapper.deleteById(tableBId);
        dataTableMapper.deleteById(tableCId);
        dataTableMapper.deleteById(tableVerifyId);
        dataTableMapper.deleteById(tableAnalyzeId);
        System.out.println("✅ 清理完成\n");

        System.out.println(createSep(80));
        System.out.println("✅ 所有测试通过！");
        System.out.println(createSep(80) + "\n");
    }

    private Long createTable(String name, String layer, String comment) {
        DataTable table = new DataTable();
        table.setTableName(name);
        table.setDbName("test_db");
        table.setLayer(layer);
        table.setTableComment(comment + " - 集成测试");
        table.setOwner("test_user");
        table.setStatus("active");
        dataTableMapper.insert(table);
        System.out.println("  ✓ 创建表: " + name + " (ID: " + table.getId() + ")");
        return table.getId();
    }

    private Long createTask(String code, String name, String sql,
                           List<Long> inputs, List<Long> outputs) {
        DataTask task = new DataTask();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setTaskType("batch");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SQL");
        task.setDatasourceName("doris_test");
        task.setDatasourceType("DORIS");
        task.setTaskSql(sql);
        task.setTaskDesc(name + " - 集成测试");
        task.setPriority(5);
        task.setTimeoutSeconds(600);
        task.setRetryTimes(1);
        task.setRetryInterval(60);
        task.setOwner("test_user");

        DataTask created = dataTaskService.create(task, inputs, outputs);
        System.out.println("  ✓ 创建任务: " + code + " (ID: " + created.getId() + ")");
        return created.getId();
    }

    private void verifyLineage(Long taskId, Long inputTableId, Long outputTableId, String desc) {
        List<DataLineage> inputs = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, taskId)
                .eq(DataLineage::getLineageType, "input")
        );

        if (inputTableId != null) {
            assertEquals(1, inputs.size());
            assertEquals(inputTableId, inputs.get(0).getUpstreamTableId());
        }

        if (outputTableId != null) {
            List<DataLineage> outputs = dataLineageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                    .eq(DataLineage::getTaskId, taskId)
                    .eq(DataLineage::getLineageType, "output")
            );
            assertEquals(1, outputs.size());
            assertEquals(outputTableId, outputs.get(0).getDownstreamTableId());
        }

        System.out.println("  ✓ 验证血缘: " + desc);
    }

    private void cleanupOldTestData() {
        System.out.println("\n[清理] 删除旧测试数据\n");

        List<DataTask> oldTasks = dataTaskMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataTask>()
                .and(wrapper -> wrapper
                    .like(DataTask::getTaskCode, "test_task_%")
                    .or()
                    .like(DataTask::getTaskCode, "sample_%"))
        );
        for (DataTask task : oldTasks) {
            dataLineageMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                    .eq(DataLineage::getTaskId, task.getId())
            );
            dataTaskMapper.deleteById(task.getId());
        }

        List<DataTable> oldTables = dataTableMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataTable>()
                .like(DataTable::getTableName, "test_table_%")
        );
        for (DataTable table : oldTables) {
            dataTableMapper.deleteById(table.getId());
        }

        System.out.println("  ✓ 旧测试数据已清理\n");
    }

    private void hideExternalDolphinTasks() {
        List<DataTask> externalTasks = dataTaskMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataTask>()
                .eq(DataTask::getEngine, "dolphin")
                .notLike(DataTask::getTaskCode, "test_task_")
        );
        for (DataTask task : externalTasks) {
            hiddenDolphinTaskIds.add(task.getId());
            task.setEngine(HIDDEN_ENGINE);
            dataTaskMapper.updateById(task);
        }
    }

    private void configureDolphinSchedulerMock() {
        doNothing().when(dolphinSchedulerService).clearProjectCodeCache();
        doReturn(Collections.emptyList()).when(dolphinSchedulerService).listTaskGroups(nullable(String.class));
        doReturn(testDatasourceOptions()).when(dolphinSchedulerService)
                .listDatasources(nullable(String.class), nullable(String.class));
        doReturn(TEST_WORKFLOW_CODE).when(dolphinSchedulerService).syncWorkflow(
                anyLong(),
                anyString(),
                nullable(String.class),
                anyList(),
                anyList(),
                anyList(),
                nullable(String.class));
        doNothing().when(dolphinSchedulerService).setWorkflowReleaseState(anyLong(), anyString());
    }

    private List<DolphinDatasourceOption> testDatasourceOptions() {
        DolphinDatasourceOption option = new DolphinDatasourceOption();
        option.setId(1L);
        option.setName("doris_test");
        option.setType("MYSQL");
        option.setDbName("test_db");
        return Collections.singletonList(option);
    }

    private static String createSep(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append('=');
        return sb.toString();
    }
}
