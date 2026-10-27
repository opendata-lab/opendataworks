package com.onedata.portal.service.datasource.core;

import com.onedata.portal.service.table.TableEngineHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Doris 编辑处理器依赖连接门面，待单例构造完成后再校验，避免构造循环。
 */
@Component
@RequiredArgsConstructor
public class DatasourceCapabilityValidator implements SmartInitializingSingleton {

    private final DatasourceAdapterRegistry registry;
    private final List<TableEngineHandler> handlers;

    @Override
    public void afterSingletonsInstantiated() {
        registry.validateEditHandlers(handlers);
    }
}
