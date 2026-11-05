package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.dolphin.DolphinPageData;
import com.onedata.portal.dto.dolphin.DolphinProject;
import com.onedata.portal.dto.dolphin.DolphinTaskGroup;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class DolphinSchedulerServiceTest {

    @Mock private DolphinConfigService dolphinConfigService;

    @Mock private ObjectMapper objectMapper;

    @Mock private DolphinOpenApiClient openApiClient;

    private DolphinSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new DolphinSchedulerService(dolphinConfigService, objectMapper, openApiClient);

        DolphinConfig config = new DolphinConfig();
        config.setProjectName("it_project");
        lenient()
                .when(
                        dolphinConfigService.resolveActiveOrTargetConfig(
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(config);
    }

    @Test
    void listTaskGroupsShouldKeepGlobalGroupsWhenFilteringByProject() {
        DolphinProject project = new DolphinProject();
        project.setCode(100L);
        when(openApiClient.getProject(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq("it_project")))
                .thenReturn(project);

        DolphinTaskGroup currentProjectGroup = taskGroup(1, "tg-project", 100L);
        DolphinTaskGroup globalGroup = taskGroup(2, "tg-global", 0L);
        DolphinTaskGroup otherProjectGroup = taskGroup(3, "tg-other", 200L);

        DolphinPageData<DolphinTaskGroup> page = new DolphinPageData<>();
        page.setTotalList(Arrays.asList(currentProjectGroup, globalGroup, otherProjectGroup));
        when(openApiClient.listTaskGroups(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1),
                        org.mockito.ArgumentMatchers.eq(200),
                        org.mockito.ArgumentMatchers.eq(null),
                        org.mockito.ArgumentMatchers.eq(null)))
                .thenReturn(page);

        List<DolphinTaskGroupOption> options = service.listTaskGroups(null);
        assertEquals(2, options.size(), "应保留当前项目任务组 + 全局任务组");
        assertTrue(
                options.stream().anyMatch(item -> "tg-project".equals(item.getName())),
                "当前项目任务组应存在");
        assertTrue(
                options.stream().anyMatch(item -> "tg-global".equals(item.getName())),
                "全局任务组(projectCode=0)应存在");
        assertTrue(
                options.stream().noneMatch(item -> "tg-other".equals(item.getName())),
                "其他项目任务组应被过滤");
    }

    @Test
    void buildTaskDefinitionShouldDefaultFlagToYes() {
        Map<String, Object> payload =
                service.buildTaskDefinition(
                        1001L, 1, "task_default_flag", "desc", "echo ok", "MEDIUM", 1, 1, 60);

        assertEquals("YES", payload.get("flag"));
    }

    @Test
    void buildTaskDefinitionShouldKeepExplicitNoFlag() {
        Map<String, Object> payload =
                service.buildTaskDefinition(
                        1002L,
                        1,
                        "task_disabled",
                        "desc",
                        "echo ok",
                        "MEDIUM",
                        1,
                        1,
                        60,
                        "SHELL",
                        null,
                        null,
                        "NO",
                        null,
                        null);

        assertEquals("NO", payload.get("flag"));
    }

    @Test
    void extractsWorkflowInstanceIdAcrossDolphinResponseVariants() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertEquals(
                "101",
                service.extractWorkflowInstanceId(mapper.readTree("{\"processInstanceId\":101}")));
        assertEquals(
                "102",
                service.extractWorkflowInstanceId(mapper.readTree("{\"workflowInstanceId\":102}")));
        assertEquals("103", service.extractWorkflowInstanceId(mapper.readTree("103")));
    }

    private DolphinTaskGroup taskGroup(int id, String name, Long projectCode) {
        DolphinTaskGroup group = new DolphinTaskGroup();
        group.setId(id);
        group.setName(name);
        group.setProjectCode(projectCode);
        group.setGroupSize(5);
        group.setUseSize(0);
        group.setStatus("1");
        return group;
    }
}
