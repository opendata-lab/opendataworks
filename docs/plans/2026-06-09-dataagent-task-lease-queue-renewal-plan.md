# DataAgent 任务租约排队续租修复执行计划

- 配套设计: `docs/design/2026-06-09-dataagent-task-lease-queue-renewal-design.md`
- 日期: 2026-06-09

## 任务

1. 调整 `dataagent/dataagent-backend/core/task_coordinator.py`:
   - `_run_task`:在进入并发信号量之前启动心跳/续租循环;新增 `running` 事件;拿到信号量后若 `lease_lost` 已置位则退让;将 `_release_lease` 与取消键清理收敛到统一 `finally`。
   - 拆分续租与 DB 心跳:新增 `_heartbeat_tick`,排队阶段只续约 Redis 租约,执行阶段同时写 DB 心跳。
   - `_heartbeat_loop` 新增 `running` 参数,循环体改为调用 `_heartbeat_tick`。
2. 新增回归测试 `dataagent/dataagent-backend/tests/test_task_coordinator.py`:
   - 排队阶段不写 DB 心跳、但续约 Redis 租约;
   - 执行阶段写 DB 心跳并续约;
   - 租约被他人持有时 `_heartbeat_tick` 返回 `False`。

## 触达文件

- `dataagent/dataagent-backend/core/task_coordinator.py`
- `dataagent/dataagent-backend/tests/test_task_coordinator.py`
- `docs/design/2026-06-09-dataagent-task-lease-queue-renewal-design.md`
- `docs/plans/2026-06-09-dataagent-task-lease-queue-renewal-plan.md`

## 验证

- 运行 `pytest tests/test_task_coordinator.py`(无需真实 Redis/MySQL,使用 fake)。
- 若本地可启动 Redis + MySQL,补充并发压过槽位的全链路冒烟:提交 > `task_max_concurrency` 个后台任务,确认排队任务最终 `success`,不再出现 `task_cancelled` 误判。

## 回滚

- 改动集中在 `_run_task` / `_heartbeat_loop` 与新增测试,直接 revert 对应提交即可恢复原行为。
