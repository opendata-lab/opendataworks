package com.onedata.portal.service;

import com.onedata.portal.dto.TableColumnRequest;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.table.TableEngineHandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableCreateServiceTest {

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private DataFieldMapper dataFieldMapper;

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private TableNameGeneratorService tableNameGeneratorService;

    @Mock
    private TableEngineHandlerRegistry tableEngineHandlerRegistry;

    @Mock
    private TableMetadataVersionService tableMetadataVersionService;

    private TableCreateService service;

    @BeforeEach
    void setUp() {
        service = new TableCreateService(
                dataTableMapper,
                dataFieldMapper,
                dorisClusterMapper,
                tableNameGeneratorService,
                tableEngineHandlerRegistry,
                tableMetadataVersionService);
    }

    @Test
    void createRejectsMysqlDatasourceForDorisDesigner() {
        TableCreateRequest request = request();
        request.setDorisClusterId(8L);
        when(dorisClusterMapper.selectById(8L)).thenReturn(cluster("MYSQL"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.create(request));

        assertEquals("表设计器仅支持 Doris 数据源: MYSQL", exception.getMessage());
    }

    @Test
    void createRequiresDatasourceWhenSyncingToDoris() {
        TableCreateRequest request = request();
        request.setSyncToDoris(true);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.create(request));

        assertEquals("请指定 Doris 数据源", exception.getMessage());
    }

    private TableCreateRequest request() {
        TableColumnRequest column = new TableColumnRequest();
        column.setColumnName("id");
        column.setDataType("BIGINT");

        TableCreateRequest request = new TableCreateRequest();
        request.setLayer("DWD");
        request.setDbName("dw");
        request.setColumns(Collections.singletonList(column));
        return request;
    }

    private DorisCluster cluster(String sourceType) {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(8L);
        cluster.setClusterName("test");
        cluster.setSourceType(sourceType);
        return cluster;
    }
}
