package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.entity.SysConfig;
import com.onedata.portal.mapper.SysConfigMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 工作流运行态绑定的全局互斥锁。
 *
 * <p>凡是会改变"某个 Dolphin 运行态归谁所有"的写路径，都必须先取这把锁：
 * <ul>
 *   <li>导入时把工作流绑定到目标 Dolphin 的既有运行态</li>
 *   <li>发布或审批通过时创建、更新并固化 Dolphin 运行态绑定</li>
 *   <li>切换调度环境时清空旧运行态并把工作流指向目标 Dolphin</li>
 *   <li>修改或删除 Dolphin 环境（两者都是"先统计绑定数量、再写"的读改写）</li>
 *   <li>{@code WorkflowRuntimeSyncService} 当前无对外入口；未来接入时也必须在首次读库前取锁</li>
 * </ul>
 *
 * <p>为什么不按 Dolphin 环境分别加锁：占用判定落在
 * {@code idx_data_workflow_runtime (project_code, workflow_code)} 上，不同环境完全可能出现相同的
 * project/workflow 编码，此时按环境加锁的两个事务会锁住不同的配置行，却对同一个索引间隙执行
 * {@code FOR UPDATE}，跨环境并发仍会死锁。绑定运行态是低频人工操作，直接全局串行更简单也更可靠。
 *
 * <p>为什么不靠占用查询自己的 {@code FOR UPDATE}：目标运行态尚未被占用时它命中空结果，
 * InnoDB 只能给出间隙锁，而间隙锁是纯抑制性的、可被多个事务同时持有，起不到互斥作用。
 */
@Component
@RequiredArgsConstructor
public class RuntimeBindingLock {

    static final String LOCK_KEY = "workflow.runtime_binding.lock";

    private final SysConfigMapper sysConfigMapper;

    /**
     * 取排他行锁，随调用方的事务一起释放。必须在已开启的事务中调用，否则锁会立即释放而失去意义。
     */
    public void acquire() {
        List<SysConfig> locked = sysConfigMapper.selectList(
                Wrappers.<SysConfig>lambdaQuery()
                        .eq(SysConfig::getConfigKey, LOCK_KEY)
                        .last("FOR UPDATE"));
        if (locked == null || locked.isEmpty()) {
            throw new IllegalStateException(
                    "运行态绑定锁记录缺失（sys_config." + LOCK_KEY + "），请确认数据库迁移已执行");
        }
    }
}
