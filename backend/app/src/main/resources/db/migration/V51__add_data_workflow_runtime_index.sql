-- 导入时按 (project_code, workflow_code) 判定 Dolphin 运行态是否已被平台工作流占用。
-- 该查询在提交阶段用 SELECT ... FOR UPDATE 读取，目的是绕开 REPEATABLE READ 的事务快照、
-- 读到最新已提交数据；没有这个索引它会退化成全表扫描并锁住整张表。
--
-- 刻意保持非唯一：data_workflow 是逻辑删除，被软删的行仍持有 workflow_code，
-- 唯一约束会让"删除后再重新关联同一运行态"直接失败。
SET @has_runtime_index := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_workflow'
      AND INDEX_NAME = 'idx_data_workflow_runtime'
);

SET @sql_add_runtime_index := IF(
    @has_runtime_index = 0,
    'CREATE INDEX `idx_data_workflow_runtime` ON `data_workflow` (`project_code`, `workflow_code`)',
    'SELECT 1'
);

PREPARE stmt FROM @sql_add_runtime_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 运行态绑定的全局互斥点。
--
-- 上面那条 FOR UPDATE 只解决"读到最新数据"，不构成互斥：目标运行态尚未被占用时它命中空结果，
-- InnoDB 只能给出间隙锁，而间隙锁是纯抑制性的、可被多个事务同时持有。两个并发导入会双双读到空，
-- 随后把 workflow_code 从 NULL 改成同一个值时，在 REPEATABLE READ 下互相等待对方的间隙锁而死锁，
-- 在 READ COMMITTED 下则因为检索通常不加间隙锁而双双绑定成功。
--
-- 锁一行真实存在的记录才是真正互斥，且不依赖隔离级别。这里用一行全局记录而不是按 Dolphin 环境
-- 分别加锁：不同环境可能出现相同的 project_code + workflow_code，按环境加锁时两者会落在同一个
-- 索引间隙上，跨环境并发仍会死锁。绑定运行态是低频人工操作，全局串行的代价可以接受。
--
-- 该行同时被 Dolphin 环境的修改与删除路径持有，使"检查是否已被绑定 -> 写入"不再是竞态读改写。
INSERT INTO `sys_config` (`config_key`, `config_value`, `description`)
SELECT 'workflow.runtime_binding.lock', '1', '工作流运行态绑定的全局互斥行，仅用于加锁，不读取取值'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_config` WHERE `config_key` = 'workflow.runtime_binding.lock'
);

-- 回填仍然为空的 dolphin_config_id。
--
-- V43 做过一次同样的回填，但它只在当时存在默认环境时才执行；此后 WorkflowDeployService
-- 发布成功时也只写 workflow_code、不写 dolphin_config_id，于是还会不断产生新的空配置绑定。
-- 这些工作流实际跟随"当前默认环境"运行，一旦默认环境被改身份、删除或切换，它们会静默指向
-- 另一套 Dolphin，而"是否已被运行态绑定"的检查按 dolphin_config_id 匹配，根本看不到它们。
-- 这里把已经绑定运行态的空配置行归属到当前默认环境；本次改动同时让发布路径写回该字段，
-- 之后不再新增空配置绑定。
-- "有效默认环境"必须和运行时的 DolphinConfigService.getDefaultConfig 保持同一套判定：
-- 优先显式 is_default=1，没有显式默认时回落到最新的启用配置。只认显式标记的话，
-- 未设默认的环境下工作流其实已经跟着某个配置跑，迁移却什么都不回填。
SET @default_dolphin_config_id := (
    SELECT `id` FROM `dolphin_config`
    WHERE `is_active` = 1 AND (`deleted` IS NULL OR `deleted` = 0)
    ORDER BY `is_default` DESC, `id` DESC
    LIMIT 1
);

UPDATE `data_workflow`
SET `dolphin_config_id` = @default_dolphin_config_id
WHERE `dolphin_config_id` IS NULL
  AND `workflow_code` IS NOT NULL
  AND `workflow_code` > 0
  AND (`deleted` IS NULL OR `deleted` = 0)
  AND @default_dolphin_config_id IS NOT NULL;
