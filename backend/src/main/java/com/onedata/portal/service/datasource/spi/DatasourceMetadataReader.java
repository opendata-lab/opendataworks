package com.onedata.portal.service.datasource.spi;

import com.onedata.portal.service.datasource.model.ColumnMetadata;
import com.onedata.portal.service.datasource.model.Namespace;
import com.onedata.portal.service.datasource.model.TableMetadata;

import java.sql.Connection;
import java.util.List;

public interface DatasourceMetadataReader {

    List<Namespace> namespaces(Connection connection);

    List<TableMetadata> tables(Connection connection, String namespace);

    List<ColumnMetadata> columns(Connection connection, String namespace, String table);

    List<TableMetadata> objects(Connection connection, String keyword);
}
