package com.onedata.portal.service.datasource.spi;

import com.onedata.portal.service.datasource.model.ColumnMetadata;
import com.onedata.portal.service.datasource.model.Namespace;
import com.onedata.portal.service.datasource.model.TableMetadata;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DatasourceMetadataReader {

    List<Namespace> namespaces(Connection connection);

    List<TableMetadata> tables(Connection connection, String namespace);

    List<ColumnMetadata> columns(Connection connection, String namespace, String table);

    List<TableMetadata> objects(Connection connection, String keyword);

    /**
     * 当前连接账号可见的全部字段，按 "namespace\u0000table" 分组；一次查询取回，供按账号求交集。
     */
    Map<String, Set<String>> visibleColumns(Connection connection);
}
