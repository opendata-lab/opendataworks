# Pi 运行时迁到 pi-coding-agent SDK

日期：2026-10-25。状态：**暂缓（2026-10-26）**。方向已确认，与 [服务化架构](2026-10-25-dataagent-service-architecture-design.md) 合并实施，先完成问题卡片与计划卡片。重新开工前，按第 8 节的评审结论修订第 4、6、7 节。
实施计划：`docs/plans/2026-10-25-pi-coding-agent-migration-plan.md`。

## 1. 目标

`dataagent-runtime-pi` 现在直接建在 `@earendil-works/pi-agent-core` 之上：

- 只用了它的 Agent 循环；
- 技能加载、MCP 客户端、上下文压缩、会话回放、工具、提问与计划都是自己写的，`src/` 合计约 4200 行；
- 新需求（问题卡片、计划模式、等待期间释放资源）还会继续加自研代码。

`@earendil-works/pi-coding-agent` 是同一作者在 `pi-agent-core` 之上做的完整 Agent，提供 SDK 方式嵌入。它已经覆盖这些能力：

- 会话树与恢复；
- 自动压缩；
- Agent Skills 标准；
- MCP 扩展；
- 工具执行可替换；
- 扩展事件系统；
- 交互对话框协议；
- 官方的权限、计划、沙箱、子代理示例。

本次迁移的原则：**能用官方机制的不自己写；只保留数据场景特有的部分**，即 SQL 结果折叠、工作区边界策略，以及与 Python 控制面的中立事件契约。

不在范围内：
- Claude Code 引擎；
- Python 控制面的任务调度；
- 前端组件。

问题卡片、计划卡片按 `2026-10-25-dataagent-plan-and-question-cards-design.md` 实现，迁移后其中 Pi 一侧的实现方式会变，界面和协议不变。

## 2. 调研依据

调研对象为 pi-coding-agent 1.4.0，与当前 pi-agent-core 1.4.0 同一版本线，资料包括 npm 包内的 `docs/` 和 `examples/`。

| 官方能力 | 说明 | 依据 |
|---|---|---|
| SDK 嵌入 | `createAgentSession()` 可逐项替换：`modelRuntime`、`settingsManager`、`sessionManager`、`resourceLoader`、`tools`、`customTools` | `docs/sdk.md`，`examples/sdk/12-full-control.ts` |
| 会话 | `SessionManager.inMemory(cwd, options, entries)` 可用外部保存的条目恢复完整上下文，包括工具调用和结果、压缩摘要、分支 | `docs/sessions.md`，`docs/session-format.md` |
| 压缩 | 超过 `contextWindow - reserveTokens` 时自动压缩，保留最近 `keepRecentTokens`；`session_before_compact` 可自定义摘要 | `docs/compaction.md` |
| 技能 | 遵循 Agent Skills 规范：系统提示只列名称、描述和路径，模型按需读 `SKILL.md`；技能列表由 `resourceLoader.getSkills()` 提供 | `docs/skills.md` |
| MCP | `createMcpExtension()` 加 `pi.registerMcpServer(name, {url, headers})`：支持 HTTP 和 stdio、重试与重连、工具列表变更、按需激活（`tool_search`） | `docs/mcp.md`，`docs/extensions.md#mcp-servers` |
| 工具执行可替换 | 内置 `read`/`ls`/`grep`/`find`/`bash`/`edit`/`write` 都接受 `operations`，可以把执行路由到沙箱或微虚拟机 | `examples/extensions/sandbox`，`examples/extensions/gondolin` |
| 扩展事件 | 覆盖输入、开始运行、上下文、工具调用（可拦截、可改入参）、工具结果（可改写）、轮次结束、运行收尾 | `docs/extensions.md` |
| 工具暴露方式 | `direct`、`model-only`（官方建议用于“向用户提问”这类工具）、`deferred`、`hidden`；MCP 工具注解可用于权限判断 | `docs/extensions.md#tool-exposure` |
| 交互对话框 | `ctx.ui.select`/`confirm`/`input`/`editor`，可带超时；宿主通过 `bindExtensions({uiContext})` 自己实现 | `docs/rpc-extension-ui.md`，`AgentSession.bindExtensions` |
| 官方示例 | 计划模式、权限确认、敏感路径保护、危险操作确认、沙箱、子代理、自定义压缩、结构化输出、待办 | `examples/extensions/` |

社区包 `pi-ask-user-question`、`pi-plan-mode`、`pi-agent-modes` 都是为 CLI 写的扩展：
- 依赖 `ctx.ui` 的单选、确认，或终端界面包 `pi-tui`；
- 只支持单个问题，选项是纯字符串。

它们表达不了截图里的问题卡片（多题、选项说明、推荐项、跳过），所以**只作参考，不作依赖**。值得借用的是它们的结构：计划模式的启停、拦截规则和批准后解除。

## 3. 现有代码怎么处理

| 模块（行数） | 迁移后 | 用到的官方机制 |
|---|---|---|
| `kernel/cell.ts`（474） | 重写为薄的会话包装：建会话、绑定扩展、转发事件 | `createAgentSession`、`session.subscribe` |
| `tools/tool-registry.ts`（410）里的 Read、LS、Bash | 删除，改用内置工具，通过 `operations` 接到边界检查和沙箱 | 内置工具加 `operations` |
| `policy/workspace-boundary-enforcer.ts`（294） | 保留策略判断，改成 `tool_call` 拦截扩展，判断不了时一律拒绝 | `pi.on("tool_call")` |
| `skills/skill-loader.ts`（80） | 删除 | `resourceLoader.getSkills()`，只给本轮技能 |
| `mcp/portal-mcp-client.ts`（366），`mcp/probe-cli.ts`（44） | 删除；连接检测改用 MCP 扩展的连接状态 | `createMcpExtension`、`registerMcpServer` |
| `context/compaction-session.ts`（192），`context/context-pruner.ts`（329） | 删除，改用内置自动压缩；内置压缩达不到效果时，再用 `session_before_compact` 定制 | 设置里的 `compaction` 项 |
| `context/tabular-digest.ts`（359），`context/result-store.ts`（442），`tools/fetch-tool-result.ts`（151） | **保留**（数据场景特有），折叠改为 `tool_result` 扩展 | `pi.on("tool_result")` |
| `kernel/ui-tool-result-registry.ts`（67） | 改为扩展内部状态：原文进界面记录，摘要进模型上下文 | `tool_result`，`details` |
| `kernel/event-normalizer.ts`（294），`kernel/run-state-machine.ts`（59） | 保留，输入改为会话事件；中立事件契约不变 | `session.subscribe` |
| `providers/stream-fn-resolver.ts`（179） | 先保留，后续改用 `registerProvider` / `ModelRuntime` 注册数据库里配置的模型（见第 6 节第 2 项） | `pi.registerProvider` |
| `interaction/*`（170，本周新增） | 工具改为扩展注册，暴露方式为 `model-only`；计划模式改为扩展；`ctx.ui` 交给宿主实现的对话框桥（见 4.4） | `registerTool`、`tool_call`、`before_agent_start`、`bindExtensions` |
| `protocol/*`（233） | 保留：与 Python 的帧协议不变 | — |

预计删掉约 1500 行自研代码，换来完整会话恢复、MCP 重连与按需加载，以及第三方扩展的接入能力。

## 4. 方案

### 4.1 进程形态：SDK 嵌入 Cell，不用 RPC 模式

我们比较过两种方式。

**A. Cell 里用 SDK 嵌入（选这个）**
- Cell 仍是 Python 拉起的 Node 子进程，帧协议和中立事件契约不变，Python 侧和前端几乎不动；
- 模型凭据、MCP 配置、技能、设置全部由宿主显式注入，不读取 `~/.pi` 和项目目录。

**B. 直接运行 `pi --mode rpc`**
- 优点：连 Cell 的入口都不用写；
- 缺点：CLI 默认会扫描用户目录、项目目录、`AGENTS.md` 和 `mcp.json`，要逐项用参数关掉，任何一项漏关都会把工作区内容当成指令或配置加载进来；事件格式也要在 Python 侧重新适配。

A 的可控性更好。A 的对话框桥沿用官方 RPC 对话框的字段形状（`select`、`confirm`、`input`、`editor`、`timeout`），以后要改成 B，协议不用重做。

### 4.2 会话：显式配置，不做任何自动发现

```ts
createAgentSession({
  cwd: workspaceRoot,
  agentDir: <运行目录下的临时目录>,
  model, modelRuntime,
  settingsManager: SettingsManager.inMemory({ compaction: {...}, retry: {...} }),
  sessionManager: SessionManager.inMemory(cwd, undefined, entries),  // entries 来自 MySQL
  resourceLoader: {
    getSkills:      本轮技能（name、description、SKILL.md 路径）,
    getAgentsFiles: 空,          // 工作区里的 AGENTS.md 是数据，不是指令
    getExtensions:  只有我们打包的扩展,
    getSystemPrompt: 平台系统提示, ...
  },
  tools: ["read", "ls", "grep", "find", "bash"],   // 不给 edit/write，与现状一致
})
```

- 关闭：会话文件、项目信任提示、设置文件、`auth.json`、`codemode`。
- `tool_search` 先不启用，MCP 工具变多以后再评估。

### 4.3 会话条目存进 MySQL，作为唯一状态

- 每轮结束后，把本轮新增的会话条目写进事件表：新增记录类型 `session_entry`，或单独建表。
- 下一轮用这些条目恢复会话，不再只回放纯文本历史，所以之前轮次的工具调用、结果和压缩摘要都在上下文里。
- 这也让问题卡片设计里的 PQ-06（等待期间释放资源）可以实现：停住的运行把条目落库后退出进程；用户回答后，用这些条目重建会话，把回答作为该工具的结果接着运行。
- 这与服务化架构设计（`2026-10-25-dataagent-service-architecture-design.md`）中“事件日志是唯一状态”的结论一致。

### 4.4 交互：工具用我们的，对话框走官方接口

- `AskUserQuestion` 和 `ExitPlanMode` 用 `pi.registerTool` 注册，暴露方式为 `model-only`；执行时通过中间转发层（broker）发出 `interaction.request`，Python 侧的处理和卡片都不变。
- 计划模式写成扩展，结构参考官方 `plan-mode` 示例：
  - `before_agent_start` 追加计划模式说明，并用 `setActiveTools` 控制可用工具；
  - `tool_call` 在批准前拦截写入类工具，判断依据是平台给的写入类工具清单，加上 MCP 工具注解里的 `readOnlyHint` / `destructiveHint`；
  - 批准后解除拦截。
- **宿主实现的对话框**：`bindExtensions({ uiContext })` 把 `select`/`confirm`/`input`/`editor` 转成 `interaction.request`，界面上用通用对话卡片显示。这样官方或第三方扩展里的确认对话框（权限确认、危险操作确认、敏感路径保护等）不用改就能在网页上用。终端专用的 `custom()`、页脚、主题等方法统一做成空操作。

### 4.5 工具执行与沙箱

- 内置工具的 `operations` 是唯一的执行出口。
- 第一步接本地边界检查（沿用现在的策略）。
- 等服务化架构的 SA-01 定下来以后，再把 `operations` 接到沙箱服务的 `execute`，有两条路可选：
  - 系统级沙箱：官方 `sandbox` 示例使用 `@anthropic-ai/sandbox-runtime`，Linux 下用 bubblewrap，macOS 下用 sandbox-exec；
  - 微虚拟机：官方 `gondolin` 示例。
- 这两条路都有官方实现可以参考。

### 4.6 限额与超时

- 总超时和空闲超时仍由 Python 控制；等待用户期间暂停计时，与问题卡片设计相同。
- 最大轮次在 `turn_end` 判断，最大工具调用次数在 `tool_call` 计数，超限时拦截，并给出与现在相同的错误码，便于界面和监控沿用。

## 5. 契约不变的部分

- Python 与 Cell 之间的帧协议：`cell.init`、`run.event`、`run.heartbeat`、`run.settled`、`interaction.request` / `interaction.resolved`。
- 中立事件（`dataagent/contracts/agent-events/v1`）及其在 `da_agent_sdk_record` 中的存储方式。
- 工作区边界一致性用例（`dataagent/contracts/boundary/v1/conformance-cases.json`）。
- 前端的对话组件和卡片。

## 6. 风险与待验证

| 编号 | 问题 | 怎么验证 |
|---|---|---|
| MG-01 | 同一条回复里的多个工具调用默认可能并行执行；我们要求顺序执行，因为工作区状态和事件顺序要确定 | 试验中确认有没有顺序执行的设置；没有的话，在 `tool_call` 里加锁让它们排队 |
| MG-02 | 数据库里配置的模型（OpenAI 兼容地址、DeepSeek 等）能不能不落文件，直接在运行时注册 | 试验：`registerProvider` 加 `setRuntimeApiKey`，凭据只放进程内存 |
| MG-03 | 依赖变重：带上终端界面、图片处理、QuickJS、`codemode`，安装包约 25 MB，会进两个镜像 | 量一下镜像增量；必要时用打包裁剪 |
| MG-04 | 版本变动快（`latest` 现为 1.4.0，`legacy-node20` 为 0.74.2；1.4.0 要求 Node ≥ 22.19.0） | 精确锁定版本；升级沿用 `2026-10-22-pi-runtime-1-4-upgrade-design.md` 的流程 |
| MG-05 | 安全：默认会发现并加载 `AGENTS.md`、扩展、`mcp.json` | 用完全自定义的资源加载器；加一条测试：工作区里放 `AGENTS.md` 和 `.pi/`，确认都不生效 |
| MG-06 | 内置压缩的摘要质量和 token 成本能否替代现在的裁剪策略 | 用现有压缩测试数据和一次真实长会话做对比；真实会话要先确认预算 |
| MG-07 | 会话条目体积大（包含工具结果），每轮写库的量会变大 | 折叠后的结果才进条目；统计一次真实会话的条目大小 |

## 7. 已确认决定

| 编号 | 决定 | 状态 |
|---|---|---|
| MG-D1 | Pi 引擎迁到 pi-coding-agent SDK，原则是能用官方机制就不自研 | 已确认（用户 2026-10-25） |
| MG-D2 | 用 SDK 嵌入 Cell，不用 RPC 模式；宿主显式注入全部资源，不做任何自动发现 | 建议，待试验确认 |
| MG-D3 | 社区的提问和计划扩展只作参考；`AskUserQuestion`、`ExitPlanMode` 用自己的工具，交互走对话框桥 | 建议 |
| MG-D4 | 会话条目存进 MySQL，作为恢复依据 | 建议，待 MG-07 |

## 8. 评审结论（2026-10-26，codex，未经试验）

依据是 pi-coding-agent 1.4.0 npm 包的 `dist/`、`docs/` 和当前仓库的 pi-agent-core 1.4.0 源码。第 0 步试验仍要逐项验证。

### 8.1 待验证项

| 编号 | 结论 | 要点 | 设计要改的地方 |
|---|---|---|---|
| MG-01 | 成立 | pi-agent-core 默认并行（`agent.js` 中 `toolExecution ?? "parallel"`）；现有 Cell 已设为 `sequential` | 首次 prompt 前设 `session.agent.toolExecution = "sequential"`；删除“在 `tool_call` 里加锁”的备选，那样可能死锁 |
| MG-02 | 成立 | `ModelRuntime.create({credentials, modelsPath: null, refreshOnCreate: false})`，再 `registerProvider`、`await setRuntimeApiKey()`；凭据用 pi-ai 的 `InMemoryCredentialStore` | `AuthStorage` 未从包根导出，不要按旧示例写 |
| MG-03 | 部分成立 | 包本身约 22 MB；加上新增依赖至少约 37 MB（估算，没有在镜像里实测）；关掉功能不会少装依赖 | 预算按 40 MB 左右估，以镜像实测为准 |
| MG-04 | 部分成立 | 依赖都精确锁在 1.4.0，没有 peer 依赖 | 镜像和测试要覆盖 Node 22.19 |
| MG-05 | 部分成立 | 能关掉，但没有总开关。MCP 扩展自己会读全局 `mcp.json`、受信项目的 `.pi/mcp.json` | 资源加载器、设置、会话、模型全部自己提供；**覆盖 MCP 扩展的 `loadConfig`**，并单独处理它的凭据和日志存储；临时 `agentDir` 不能算隔离 |
| MG-06 | 部分成立 | 默认 `reserveTokens = 16384`、`keepRecentTokens = 20000`，超过“上下文窗口 − reserveTokens”时触发；`session_before_compact` 可以取消压缩或返回自定义结果 | 只替代会话摘要，逐个工具结果的折叠保留；删除旧压缩模块前先做对比 |
| MG-07 | 部分成立 | `getHeader()`、`getEntries()` 可导出，`inMemory(cwd, options, entries)` 可导入，条目里有完整工具结果 | 另外保存 `getLeafId()`；**不能恢复执行中的工具调用**（等待中的交互、同一批里还没跑的工具），首期只恢复已完成的对话 |

### 8.2 决定的调整

| 编号 | 建议 |
|---|---|
| MG-D2 | 确认，补上 MG-05 的隔离条件 |
| MG-D3 | 调整：卡片工具自己写；`uiContext` 对话框桥要改前端和协议，单独排期 |
| MG-D4 | 调整：MySQL 里存会话树、header 和 leaf；“断开后接着执行原工具”另做设计 |

### 8.3 接口核对

- 存在，签名与设计一致：`createAgentSession`（返回 `{session, extensionsResult}`）、`SessionManager.inMemory(cwd, options, entries)`、`createMcpExtension`、`registerMcpServer(name, config)`、`bindExtensions({uiContext})`（要 await）、`tool_call`、`tool_result`、`session_before_compact`。
- 与设计不同：
  - `getSkills()` 返回 `{skills, diagnostics}`；
  - `editor(title, prefill?)` 不支持超时；
  - `select`、`confirm`、`input` 的超时单位是毫秒；
  - `turn_end` 的返回值是 `{entries?, continue?}`；
  - `before_agent_start` 返回的 `systemPrompt` 会整段替换原提示；
  - 一轮是否真正结束看 `agent_settled`，不看 `agent_end`。
- 工具白名单：`tools: [...]` 是允许列表，写成 4.2 的示例会把 `AskUserQuestion`、`ExitPlanMode` 和 MCP 工具都过滤掉。要列全，MCP 首期配 `exposure: "direct"`（默认是 `codemode`）。
- `operations` 不是唯一的执行出口：扩展可以自己执行代码，MCP 有独立的传输，`read` 的路径解析直接调用本地 `fs`。隔离不能只靠 `operations`。

### 8.4 范围

计划第 4、5 步新增的 `session.entries` 帧、`kind: "dialog"` 和前端通用对话卡片都是跨层变更，与第 5 节“契约不变”冲突。合并实施时要写明协议版本和发布顺序。

