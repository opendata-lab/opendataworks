package com.onedata.portal.service.inspection;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.service.DatasourceAdapterFixtures;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableNamingRuleHandlerTest {

    @Mock
    private InspectionSupport support;

    @Mock
    private DataTableMapper dataTableMapper;

    @Spy
    private DatasourceAdapterRegistry adapterRegistry =
            DatasourceAdapterFixtures.registry(new DorisJdbcProperties());

    @InjectMocks
    private TableNamingRuleHandler handler;

    private static DataTable table(Long id, String name) {
        DataTable table = new DataTable();
        table.setId(id);
        table.setTableName(name);
        table.setStatus("active");
        return table;
    }

    @Test
    void ruleTypeMatchesRegistryKey() {
        assertEquals("table_naming", handler.ruleType());
    }

    @Test
    void flagsOnlyTablesViolatingDefaultPattern() {
        InspectionRule rule = new InspectionRule();
        rule.setRuleType("table_naming");

        when(support.parseRuleConfig(any())).thenReturn(new HashMap<>());
        when(dataTableMapper.selectList(any())).thenReturn(Arrays.asList(
                table(1L, "ods_user"),   // 符合默认正则 ^(ods|dwd|dim|dws|ads)_...
                table(2L, "BadName")));  // 违规
        when(support.createIssue(any(), any(), any())).thenAnswer(inv -> new InspectionIssue());

        List<InspectionIssue> issues = handler.check(100L, rule);

        assertEquals(1, issues.size());
        verify(support, times(1)).createIssue(any(), any(), any());
        verify(support, times(1)).insertIssue(any());
    }
}
