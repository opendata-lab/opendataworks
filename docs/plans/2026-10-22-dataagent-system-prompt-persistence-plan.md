# DataAgent 系统提示落库计划

设计：[DataAgent 系统提示落库设计](../design/2026-10-22-dataagent-system-prompt-persistence-design.md)。

## 任务

1. `dataagent/dataagent-backend/alembic/versions/20261022_000028_system_prompt_persistence.py`：新建 `da_system_prompt` 表，`da_agent_task` 增加 `system_prompt_sha256` 列，并提供 downgrade。
2. `core/topic_task_store.py`：
   - `record_task_system_prompt(task_id, content)`：写入 `da_system_prompt`（`INSERT IGNORE`）并更新 `da_agent_task.system_prompt_sha256`，返回 hash；
   - `get_task_system_prompt(task_id)`：关联查询，返回内容或 `None`。
3. `core/task_executor.py`：`_build_system_prompt()` 之后调用 `record_task_system_prompt`，异常只记 warning。
4. `api/admin_routes.py`：`skills_router` 下新增 `GET /tasks/{task_id}/system-prompt`（需要管理员权限）。
5. 测试：
   - `tests/test_topic_task_store.py`：用 fake 连接校验 SQL 参数，以及相同内容只写一份；
   - `tests/test_task_executor.py`：执行时调用了持久化，并且持久化失败不影响执行；
   - 路由测试：返回 200 和 404。

## 验证

- 定向运行 `pytest`。
- 本地冒烟：执行 `alembic upgrade head`，提交一次真实任务，确认 `da_agent_task.system_prompt_sha256` 有值、`da_system_prompt` 有对应内容，并且管理员接口能取回。

## 发布与回退

- 先执行迁移，再发布代码。旧代码会忽略新列和新表。
- 回退：执行 `alembic downgrade -1`，删除新列和新表，同时撤回代码。
