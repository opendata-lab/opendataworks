package com.onedata.portal.service.permission;

import static org.mockito.Mockito.mock;

import com.onedata.portal.service.datasource.adapters.doris.DorisPermissionAdapter;
import com.onedata.portal.service.datasource.adapters.mysql.MysqlPermissionAdapter;
import com.onedata.portal.service.datasource.adapters.postgresql.PostgresqlPermissionAdapter;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;

import org.mockito.Answers;

/** A registry whose adapters only provide the real per-engine permission adapters. */
final class PermissionTestAdapters {
    private PermissionTestAdapters() {}

    static DatasourceAdapterRegistry registry() {
        return mock(
                DatasourceAdapterRegistry.class,
                call ->
                        "require".equals(call.getMethod().getName())
                                ? adapter(call.getArgument(0))
                                : Answers.RETURNS_DEFAULTS.answer(call));
    }

    private static DatasourceAdapter adapter(String type) {
        String engine = DatasourceAdapterRegistry.normalize(type);
        return mock(
                DatasourceAdapter.class,
                call ->
                        "permissions".equals(call.getMethod().getName())
                                ? permissions(engine)
                                : Answers.RETURNS_DEFAULTS.answer(call));
    }

    static NativePermissionAdapter permissions(String engine) {
        switch (engine) {
            case "MYSQL":
                return new MysqlPermissionAdapter();
            case "POSTGRESQL":
                return new PostgresqlPermissionAdapter();
            case "DORIS":
                return new DorisPermissionAdapter();
            default:
                throw new IllegalArgumentException("不支持该数据源权限管理");
        }
    }
}
