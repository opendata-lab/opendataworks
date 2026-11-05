package com.onedata.portal.service;

import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.mapper.DolphinConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DolphinConfigServiceTest {

    @Mock
    private DolphinConfigMapper dolphinConfigMapper;

    @Mock
    private RuntimeBindingLock runtimeBindingLock;

    @Test
    void setDefaultShouldPinResidualBindingsBeforeSwitching() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);

        DolphinConfig target = new DolphinConfig();
        target.setId(2L);
        target.setIsActive(true);
        DolphinConfig effectiveDefault = new DolphinConfig();
        effectiveDefault.setId(1L);
        when(dolphinConfigMapper.selectOne(any())).thenReturn(effectiveDefault);
        when(dolphinConfigMapper.selectById(2L)).thenReturn(target);

        service.setDefault(2L);

        // 互斥挡不住"先切默认、之后的发布落到新环境"这种顺序漂移，
        // 必须在切换前把仍跟随旧默认环境的工作流固定下来
        InOrder inOrder = inOrder(dolphinConfigMapper);
        inOrder.verify(dolphinConfigMapper).pinRuntimeBoundWorkflowsWithoutConfig(1L);
        inOrder.verify(dolphinConfigMapper).updateById(any());
    }

    @Test
    void deleteShouldCountOrphanBindingsWhenTargetIsCurrentDefault() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);

        DolphinConfig current = new DolphinConfig();
        current.setId(1L);
        current.setIsActive(true);
        current.setIsDefault(1);
        when(dolphinConfigMapper.selectById(1L)).thenReturn(current);
        when(dolphinConfigMapper.selectOne(any())).thenReturn(current);
        when(dolphinConfigMapper.countRuntimeBoundWorkflows(1L)).thenReturn(0L);
        // dolphin_config_id 为空但已绑定运行态的存量工作流实际跟随默认环境，
        // 删除默认环境时必须算进来，否则它们会静默指向另一套 Dolphin
        when(dolphinConfigMapper.countRuntimeBoundWorkflowsWithoutConfig()).thenReturn(2L);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.delete(1L));
        assertTrue(ex.getMessage().contains("已被运行态工作流绑定"));
        verify(dolphinConfigMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteShouldIgnoreOrphanBindingsForNonDefaultConfig() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);

        DolphinConfig target = new DolphinConfig();
        target.setId(2L);
        DolphinConfig current = new DolphinConfig();
        current.setId(1L);
        when(dolphinConfigMapper.selectById(2L)).thenReturn(target);
        when(dolphinConfigMapper.selectOne(any())).thenReturn(current);
        when(dolphinConfigMapper.countRuntimeBoundWorkflows(2L)).thenReturn(0L);

        service.delete(2L);

        verify(dolphinConfigMapper).deleteById(2L);
        verify(dolphinConfigMapper, never()).countRuntimeBoundWorkflowsWithoutConfig();
    }

    @Test
    void createShouldNormalizeDefaultsAndInsertNamedConfig() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);
        DolphinConfig config = new DolphinConfig();
        config.setConfigName(" New Dolphin ");
        config.setUrl("http://new-ds/dolphinscheduler/");
        config.setToken("token");
        config.setProjectName("new_project");
        config.setTenantCode(null);
        config.setWorkerGroup(null);
        config.setExecutionType(null);
        config.setIsActive(null);
        config.setIsDefault(1);

        service.create(config);

        ArgumentCaptor<DolphinConfig> captor = ArgumentCaptor.forClass(DolphinConfig.class);
        verify(dolphinConfigMapper).insert(captor.capture());
        DolphinConfig saved = captor.getValue();
        assertEquals("New Dolphin", saved.getConfigName());
        assertEquals("http://new-ds/dolphinscheduler", saved.getUrl());
        assertEquals("default", saved.getTenantCode());
        assertEquals("default", saved.getWorkerGroup());
        assertEquals("PARALLEL", saved.getExecutionType());
        assertEquals(Boolean.TRUE, saved.getIsActive());
        assertEquals(Integer.valueOf(1), saved.getIsDefault());
    }

    @Test
    void getEnabledConfigShouldRejectInactiveConfig() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);
        DolphinConfig inactive = new DolphinConfig();
        inactive.setId(2L);
        inactive.setConfigName("stopped");
        inactive.setIsActive(false);
        when(dolphinConfigMapper.selectById(2L)).thenReturn(inactive);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getEnabledConfig(2L));
        assertEquals("Dolphin 环境未启用: stopped", ex.getMessage());
    }

    @Test
    void deleteShouldRejectRuntimeBoundConfig() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);
        when(dolphinConfigMapper.selectById(3L)).thenReturn(activeConfig(3L));
        when(dolphinConfigMapper.countRuntimeBoundWorkflows(3L)).thenReturn(1L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.delete(3L));
        assertEquals("Dolphin 环境已被运行态工作流绑定，不能删除", ex.getMessage());
    }

    private DolphinConfig activeConfig(Long id) {
        DolphinConfig config = new DolphinConfig();
        config.setId(id);
        config.setConfigName("active");
        config.setUrl("http://ds/dolphinscheduler");
        config.setToken("token");
        config.setProjectName("opendataworks");
        config.setTenantCode("default");
        config.setWorkerGroup("default");
        config.setExecutionType("PARALLEL");
        config.setIsActive(true);
        config.setIsDefault(0);
        return config;
    }
}
