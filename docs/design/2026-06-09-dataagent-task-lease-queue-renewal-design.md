# DataAgent 任务租约排队续租修复设计

- 日期: 2026-06-09
- 范围: `dataagent/dataagent-backend` 任务协调运行时(`core/task_coordinator.py`)
- 影响级别: 中(运行时契约 / 并发与可靠性行为)

## 现状

`TaskCoordinator` 用一个 Redis 租约(`da:task:lease:{task_id}`)表示"本实例正在持有并执行该任务",TTL 由 `task_lease_ttl_seconds` 控制(默认 30s)。并发上限由 `task_max_concurrency`(默认 4)的 `asyncio.Semaphore` 控制。

执行链路:

1. `submit_task` 通过 `_acquire_lease(nx=True)` 抢到租约,把 `task_id` 放入内存队列。
2. `_queue_loop` 立即为该任务创建 `_run_task` 协程。
3. `_run_task` 先 `async with self._semaphore` 排队等待并发槽位。
4. 拿到槽位后才 `mark_task_running` 并启动 `_heartbeat_loop`,由心跳负责续租。

租约续租只在第 4 步之后发生。

## 问题

当**提交并发大于后端并发上限**(例如评测并发 8 > `task_max_concurrency` 4)时,超出的任务在第 3 步阻塞在信号量上。此阶段:

- 任务仍持有第 1 步抢到的租约,但**没有任何续租**;
- 单次 NL2SQL 运行通常需要数分钟,排队等待很容易超过 30s 的租约 TTL;
- 租约到期被 Redis 自动删除。

后果:

- 任务终于拿到槽位开始执行时,`_heartbeat_loop` 第一次 `_renew_lease` 发现租约已不存在,置 `lease_lost`,执行器据此在 `core/task_executor.py` 以 `task_status="suspended"`、`error.code="task_cancelled"`(文案"任务已取消")收尾;
- 同时 `_recover_expired_tasks` 发现该 running 任务无租约,创建恢复子任务(`task_recovered`),子任务重新排队后撞同一堵墙,形成"恢复链";
- 评测/调用方观察到"排在后面的任务连续若干次被 cancel/recover,最终报 task cancelled"。

根因:**租约的生命周期没有覆盖"排队等待并发槽位"这一阶段**,导致排队中的任务被误判为过期/被取消。

## 方案

让租约续租覆盖从入队到执行结束的整个生命周期,而不仅是执行阶段。

- 在 `_run_task` 开头(进入信号量**之前**)就启动续租循环,持续 `_renew_lease` 维持本实例对该任务的租约。
- 引入 `running` 事件区分"排队中"与"执行中"两个阶段:
  - 排队中:只续约 Redis 租约,不写 DB 心跳(`heartbeat_at` 保持准确,仅在真正执行时更新);
  - 执行中:续约 + 写 DB 心跳,行为与现状一致。
- 拿到信号量后,若 `lease_lost` 已置位(排队期间租约确实因 Redis 异常等原因丢失),则直接退让,交由恢复循环重新接管,而不是无租约硬跑。
- 将租约释放与取消标记清理统一收敛到 `finally`,保证所有提前返回路径都会释放租约。

不在本次改动内:

- 不调整 `task_max_concurrency` / `task_lease_ttl_seconds` 默认值。运维上仍建议将 `TASK_MAX_CONCURRENCY` 设为不低于期望提交并发;本设计修复的是"即便并发压过槽位也不应误判取消"的正确性问题。
- 不改动 `core/task_executor.py` 的取消语义。

## 接口与契约影响

- `_heartbeat_loop` 内部签名新增 `running` 事件参数;`_run_task` 内部结构调整。均为协调器私有方法,不涉及对外 HTTP/持久化契约。
- 租约语义更精确:"本实例从入队到完成持续持有租约",跨实例去重行为不变(入队实例全程持有租约)。
- DB `heartbeat_at` 语义不变(仍只在执行阶段更新)。

## 取舍

- 选择"排队期间持续续租"而非"延后到执行后再抢租约":前者改动最小,且保留了 `submit_task` 阶段租约带来的跨实例入队去重;后者会在入队与执行之间产生无租约窗口,增加跨实例重复入队的复杂度。
- 排队中的任务会各自占用一个轻量心跳协程,绝大多数时间在 sleep,开销可忽略。

## 验证

- 新增针对 `_heartbeat_tick` 的单元测试:排队阶段(`running` 未置位)只续约不写 DB 心跳;执行阶段两者都做;租约被他人持有时返回续约失败。
- 受环境限制无法启动完整 Redis + MySQL 全链路时,在报告中明确说明未跑端到端冒烟。
