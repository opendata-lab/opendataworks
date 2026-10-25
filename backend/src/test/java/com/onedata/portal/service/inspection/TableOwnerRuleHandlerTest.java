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
class TableOwnerRuleHandlerTest {

    @Mock
    private InspectionSupport support;

    @Mock
    private DataTableMapper dataTableMapper;

    @Spy
    private DatasourceAdapterRegistry adapterRegistry =
            DatasourceAdapterFixtures.registry(new DorisJdbcProperties());

    @InjectMocks
    private TableOwnerRuleHandler handler;

    private static DataTable table(Long id, String name) {
        DataTable table = new DataTable();
        table.setId(id);
        table.setTableName(name);
        table.setStatus("active");
        return table;
    }

    @Test
    void ruleTypeMatchesRegistryKey() {
        assertEquals("table_owner", handler.ruleType());
    }

    @Test
    void createsIssueForEveryOwnerlessTableReturnedByQuery() {
        InspectionRule rule = new InspectionRule();
        rule.setRuleType("table_owner");

        // owner 为空的过滤已在查询层完成，handler 对返回的每张表都应产生问题
        when(support.parseRuleConfig(any())).thenReturn(new HashMap<>());
        when(dataTableMapper.selectList(any())).thenReturn(Arrays.asList(
                table(1L, "ods_a"),
                table(2L, "dwd_b")));
        when(support.createIssue(any(), any(), any())).thenAnswer(inv -> new InspectionIssue());

        List<InspectionIssue> issues = handler.check(200L, rule);

        assertEquals(2, issues.size());
        verify(support, times(2)).insertIssue(any());
    }
}
