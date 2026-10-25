package com.onedata.portal.service.table;

import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for datasource-engine table handlers.
 */
@Service
public class TableEngineHandlerRegistry {

    private final Map<String, TableEngineHandler> handlers;

    public TableEngineHandlerRegistry(List<TableEngineHandler> handlerList) {
        Map<String, TableEngineHandler> registry = new LinkedHashMap<>();
        for (TableEngineHandler handler : handlerList) {
            TableEngineHandler previous = registry.put(DatasourceAdapterRegistry.normalize(handler.sourceType()), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate table engine handler for " + handler.sourceType());
            }
        }
        this.handlers = registry;
    }

    public TableEngineHandler require(String sourceType) {
        TableEngineHandler handler = handlers.get(DatasourceAdapterRegistry.normalize(sourceType));
        if (handler == null) {
            throw new RuntimeException("暂不支持数据源类型: " + sourceType);
        }
        return handler;
    }

    public TableEngineHandler find(String sourceType) {
        return handlers.get(DatasourceAdapterRegistry.normalize(sourceType));
    }
}
