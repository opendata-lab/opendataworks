# Pi 运行时升级到 1.4.0 设计

日期：2026-10-22。涉及栈：`dataagent/dataagent-runtime-pi`（Node 22、TypeScript）。实施计划见[对应计划](../plans/2026-10-22-pi-runtime-1-4-upgrade-plan.md)。

## 现状与问题

`dataagent-runtime-pi` 固定使用 `@earendil-works/pi-agent-core` 和 `@earendil-works/pi-ai` 的 `0.85.1`，npm 最新版是 `1.4.0`（2026-10-21）。后续的插件管理设计，以及评估中的 `pi-coding-agent` SDK 路线，都依赖 `1.x`。继续停在 `0.x` 会持续积累迁移成本。

## 不兼容点（在临时副本中实测）

| 变化 | 影响位置 | 处理 |
| --- | --- | --- |
| `AgentOptions.shouldStopAfterTurn` 被移除 | `src/kernel/cell.ts` 的最大轮数控制 | 按官方迁移改为 `finishTurn`：达到上限时返回 `{ action: "end" }`；`error`/`aborted` 响应直接返回，保持旧钩子"只对正常响应生效"的语义 |
| `ProviderStreams.streamSimple` 改为接收 `TranscriptContext` | `src/providers/stream-fn-resolver.ts` | 调用前用 `normalizeContext()`（`@earendil-works/pi-ai/utils/transcript`）把 `Context` 转为 `TranscriptContext`，只做这一步转换，不加兼容分支 |
| 系统提示作为第 0 条 `system` 消息进入 Agent 上下文 | `transformContext` 和 streamFn 收到的消息列表 | 见下节 |

## 系统消息进入上下文后的影响

在临时副本中用同一组输入，对比 `0.85.1` 和 `1.4.0`：

- **事件流**（Python `pi_event_writer` 落库，前端据此渲染）：两个版本逐字段一致。system 消息不产生事件，**UI 和事件存储不需要调整**。
- **上下文压缩和裁剪**（`compaction-session.ts`、`context-pruner.ts`）会收到这条 system 消息：
  - 裁剪只改写 `toolResult` 的内容，超预算时也只清空旧工具结果的内容，system 消息原样保留；
  - "首条用户消息"按 `role === "user"` 查找，不受影响；
  - 压缩复用前缀时，system 消息固定在第 0 位且内容不变，提示缓存的前缀保持稳定；
  - 字节估算算进了系统提示，压缩会比以前稍早触发，结果更接近真实占用。

用一条回归测试锁定"system 消息经过裁剪和压缩后仍原样留在第 0 位"。

## 其他影响

- `pi-ai@1.4.0` 依赖 Anthropic、OpenAI、Google、Bedrock 的 SDK，安装体积和镜像都会变大。
- Node 版本要求仍为 `>=22.19.0`，与 `.nvmrc` 一致。
- `pi-ai` 1.x 会对支持的模型在对话中途发送 system 和工具变更（Anthropic 的 `inline-tools` 测试版接口）。我们一次运行内不修改工具和系统提示，所以不会触发这条路径。缓存和成本表现以真实模型冒烟为准。

## 范围外

- 不迁移到 `pi-coding-agent` SDK，单独评估。
- 不升级 `tools/dataagent-evals/pi-kernel-spike`，它是一次性的早期验证脚本，不参与运行。
