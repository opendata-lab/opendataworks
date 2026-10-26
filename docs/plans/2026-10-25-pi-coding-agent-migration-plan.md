# Pi 运行时迁到 pi-coding-agent SDK：实施计划

设计：[2026-10-25-pi-coding-agent-migration-design.md](../design/2026-10-25-pi-coding-agent-migration-design.md)

涉及：`dataagent/dataagent-runtime-pi`（主体）；`dataagent-backend` 中的 `core/pi_runtime.py`、事件存储、会话恢复；两个镜像的 Dockerfile。前端不改。

## 0. 试验（1～2 天，先做）

在分支上新建 `src/kernel/session-cell.ts`，只验证下面几件事，不接生产路径：

1. 用完全自定义的配置建会话：设置和会话都只放内存、资源加载器自定义、不读取 `~/.pi`。确认工作区里放的 `AGENTS.md`、`.pi/`、`mcp.json` 都不会被加载（MG-05）。
2. 不落文件，把数据库里配置的模型注册进去（MG-02）。
3. 同一条回复里的多个工具调用按顺序执行（MG-01）。
4. 用 `operations` 接入现有边界检查，跑通 `boundary_conformance` 用例。
5. 用 MCP 扩展连接 portal MCP，带请求头。
6. 把会话条目导出，用 `SessionManager.inMemory(..., entries)` 恢复，确认之前的工具调用还在上下文里。
7. 量一下依赖体积（MG-03）。

产出：在设计文档第 6 节逐项填写结论，并把第 7 节的“建议”改为“已确认”或调整方案。结论不成立的项，先回到设计文档修改，再继续。

## 1. 内核替换

- `cell.ts` 改为会话包装：建会话、绑定扩展、`session.subscribe` 接到 `event-normalizer`；取消调用 `session.abort()`。
- 限额：最大轮次在 `turn_end` 判断，工具调用次数在 `tool_call` 计数，沿用现有错误码。
- 删除 `tool-registry.ts` 里的 Read、LS、Bash，改用内置工具，执行出口是 `operations`。
- 测试：`cell_run`、`tool_env`、`tool_truncation`、`boundary_conformance` 迁到新内核后全部通过；Python 侧 `test_pi_runtime_e2e` 通过。

## 2. 技能与 MCP

- 技能由资源加载器的 `getSkills()` 提供，只给本轮技能；删除 `skill-loader.ts`。系统提示里关于技能的说明，以官方格式为准。
- MCP 改用 `createMcpExtension()` 加 `registerMcpServer`，配置来自 `cell.init`。删除 `portal-mcp-client.ts`；`probe-cli.ts` 改为借助扩展检测连接状态。
- 测试：`skills`、`mcp_tools` 用例迁移；MCP 断线重连有新用例覆盖。

## 3. 上下文：压缩与折叠

- 内置压缩参数（`reserveTokens`、`keepRecentTokens`）由 `governance_settings` 映射过来。删除 `compaction-session.ts`、`context-pruner.ts`。
- SQL 结果折叠改为 `tool_result` 扩展：原文进界面记录，摘要进模型上下文，`fetch_tool_result` 和 `search_result` 保留。
- 测试：`tabular_digest`、`result_store`、`tool_output_unwrap` 保留；压缩相关用例按设计 MG-06 的对比结论调整。

## 4. 会话持久化

- Python：
  - 每轮结束后，把 Cell 回传的会话条目写进 MySQL：新增记录类型 `session_entry`，或单独建表，需要写迁移；
  - 下一轮在 `cell.init` 里带上这些条目；
  - 不再拼接纯文本历史。
- Cell：用条目恢复会话；每轮结束时通过新帧 `session.entries` 回传本轮新增的条目。
- 测试：两轮对话中，第二轮能看到第一轮的工具结果；压缩后能恢复。

## 5. 交互

- `AskUserQuestion`、`ExitPlanMode` 改为扩展注册（`model-only`），Python 侧的协议不变。
- 计划模式改为扩展：在 `before_agent_start` 追加说明，在 `tool_call` 拦截写入类工具，批准后解除。
- 对话框桥：`bindExtensions({uiContext})` 把 `select`、`confirm`、`input`、`editor` 转成 `interaction.request`（新增 `kind: "dialog"`），前端补一个通用对话卡片。
- 测试：现有 `interaction` 用例迁移；新增一个用例：官方 `confirm-destructive` 类扩展能在网页上出确认卡片。

## 6. 收尾

- 删除被替换的模块和测试，更新 `dataagent-runtime-pi/README.md`。
- 两个镜像的多阶段构建带上新依赖，量出镜像体积。
- `docs/TODO.md` 中“运行时迁到 pi-coding-agent SDK”一项移到“已完成”。
- 端到端冒烟（规则见 AGENTS.md 中智能问数的验证要求）：一次问数、一次带提问和计划的建模会话、一次多轮会话恢复。真实模型调用先确认预算。

## 发布与回退

- 每一步在分支上完成，并通过 Pi 与 Python 两侧的契约测试后再合入。不保留新旧两套内核并存，遵循仓库“一条验证过的主路径”的规则。
- 回退：还原该步的提交。会话条目表或记录类型可以保留，不影响回退后的旧逻辑。
