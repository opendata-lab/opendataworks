# Pi 运行时升级到 1.4.0 计划

设计：[Pi 运行时升级到 1.4.0 设计](../design/2026-10-22-pi-runtime-1-4-upgrade-design.md)。

## 任务

1. `dataagent/dataagent-runtime-pi/package.json`：`pi-agent-core` 和 `pi-ai` 改为 `1.4.0`，并重新生成 `package-lock.json`。
2. `src/kernel/cell.ts`：`shouldStopAfterTurn` → `finishTurn`。
3. `src/providers/stream-fn-resolver.ts`：`streamSimple` 前先调用 `normalizeContext()`。
4. 测试：
   - `test/cell_run.test.ts`：角色断言跳过开头的 `system` 消息；
   - `test/cell_run.test.ts`：新增轮数上限用例，`max_turns=1/2` 时恰好请求对应次数后结束，`max_turns<=0` 时不限轮数；
   - `test/compaction_session.test.ts`：新增用例，确认带工具声明（`toolsAdded`）的 `system` 消息经过压缩后仍在第 0 位且内容不变，首条用户消息锚点和追加后的前缀也保持不变。
5. `npm run build` 加上 `npm test` 全量通过。

## 验证

- 先 `nvm use`（Node 22.19.0），再运行 `npm test`。
- 真实模型冒烟（按 AGENTS.md 中的 Intelligent Query 冒烟方法，`DATAAGENT_RUNTIME_KIND=pi_agent_core`，`execution_mode=auto`）：
  - 提交 `你好，请直接回复 smoke-ok。`，任务状态应为 success，`da_agent_sdk_record.engine_kind=pi_agent_core`；
  - 多轮对话中的第二轮能正常回答，确认历史和系统提示的拼接正确。

## 发布与回退

- 运行时以编译产物发布，镜像需要重新构建。
- 回退：`package.json` 和 lock 改回 `0.85.1`，同时撤回上面两处代码迁移。没有数据或接口变化。
