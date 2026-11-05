package com.onedata.portal.service.lineage;

import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.DataTaskService;
import com.onedata.portal.service.SqlTableMatcherService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 依赖方向守卫。
 *
 * <p>血缘写入口的调用方（{@code DataTaskService}、{@code SqlTableMatcherService}、
 * {@code DataTableService}）都依赖 {@link TaskLineageWriteService}。写服务一旦反向依赖其中任何一个，
 * 构造器注入就会形成环，Spring 启动直接失败。这是编译期看不出来的，所以在这里固化。
 */
class LineageComponentDependencyTest {

    private List<Class<?>> constructorParamTypes(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        // Lombok @RequiredArgsConstructor 生成单一构造器
        assertEquals(1, constructors.length, type.getSimpleName() + " 应只有一个构造器");
        return Arrays.stream(constructors[0].getParameterTypes()).collect(Collectors.toList());
    }

    @Test
    void writeServiceMustNotDependOnItsCallers() {
        List<Class<?>> params = constructorParamTypes(TaskLineageWriteService.class);

        assertFalse(params.contains(DataTaskService.class),
                "TaskLineageWriteService 不能依赖 DataTaskService，否则构造器注入成环");
        assertFalse(params.contains(SqlTableMatcherService.class),
                "TaskLineageWriteService 不能依赖 SqlTableMatcherService，否则构造器注入成环");
        assertFalse(params.contains(DataTableService.class),
                "TaskLineageWriteService 不能依赖 DataTableService，否则构造器注入成环");
    }

    @Test
    void consistencyCheckerStaysReadOnlyAndDependsOnNoWriter() {
        List<Class<?>> params = constructorParamTypes(TaskLineageConsistencyChecker.class);

        assertFalse(params.contains(TaskLineageWriteService.class),
                "一致性检查是只读组件，不应持有写服务");
        assertFalse(params.contains(DataTaskService.class),
                "TaskLineageConsistencyChecker 不能依赖 DataTaskService，否则与 DataTaskService 成环");
    }
}
