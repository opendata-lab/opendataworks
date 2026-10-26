# DataAgent 服务化 Agent 架构

日期：2026-10-25。状态：**草案，待用户确认**（第 8 节的决定）。与 [pi-coding-agent 迁移](2026-10-25-pi-coding-agent-migration-design.md) 合并实施（2026-10-26 决定，暂缓，见 `docs/TODO.md`）。

## 1. 目标

把 DataAgent 收敛为服务化 Agent 的通行结构，每个事实只在一处表达。要回答：

- Agent 的配置、本次会话启用的技能、可用的 MCP 分别放在哪里，Agent 能看到什么；
- 隔离靠什么实现，只有一层；
- 凭据放在哪里，Agent 执行的命令能否拿到。

## 2. 业界做法（2026-10 调研）

### 2.1 Anthropic Managed Agents

四个概念（[概览](https://platform.claude.com/docs/en/managed-agents/overview)）：

| 概念 | 内容 | 要点 |
|---|---|---|
| Agent | `name`、`model`、`system`、`tools`、`mcp_servers`、`skills` | “可复用、带版本的配置”；配置变化即生成新版本，会话可钉住版本，也可单次覆盖而不产生新版本（[agent-setup](https://platform.claude.com/docs/en/managed-agents/agent-setup)、[sessions](https://platform.claude.com/docs/en/managed-agents/sessions)） |
| Environment | 沙箱配置：预装包、网络（`limited` + `allowed_hosts`，默认不放行任何主机） | 多个会话共用一个环境，但每个会话一个全新容器（[environments](https://platform.claude.com/docs/en/managed-agents/environments)） |
| Session | 引用 Agent 版本 + Environment + `vault_ids` | 会话之间不共享文件系统 |
| Events | 追加写的会话日志 | 编排可随时从日志恢复 |

- **编排在沙箱外**：编排（调用模型、分发工具调用的循环）离开容器，把容器当成普通工具调用，接口是 `execute(name, input) → string`；容器按需创建，挂了就作为工具错误返回给模型。编排无状态，崩溃后用 `wake(sessionId)` 读日志恢复。按需创建容器使首字延迟 p50 降约 60%、p95 降 90% 以上（[工程博客](https://www.anthropic.com/engineering/managed-agents)）。
- **凭据不进沙箱，也不进编排**：MCP 调用经专用代理，代理凭会话令牌从 vault 取凭据；Agent 定义只声明 MCP 名称和地址，凭据在创建会话时通过 vault 提供，“让秘密不进可复用的 Agent 定义”（[mcp-connector](https://platform.claude.com/docs/en/managed-agents/mcp-connector)、[vaults](https://platform.claude.com/docs/en/managed-agents/vaults)）。
- **技能按 Agent 挂载并带版本**：`skills` 每项有 `skill_id` 和 `version`，可钉版本或用 `latest`（[skills](https://platform.claude.com/docs/en/managed-agents/skills)）。

### 2.2 Claude Agent SDK 自托管

- 推荐每会话一个隔离环境；部署模式有一次性、常驻、混合（一次性容器从 `SessionStore` 恢复）、单容器多会话（[hosting](https://code.claude.com/docs/en/agent-sdk/hosting)）。
- 隔离技术按开销从低到高：sandbox-runtime（Linux 上为 bubblewrap）、Docker、gVisor、Firecracker 等虚拟机；凭据用沙箱外的代理注入请求，容器 `--network none`，只通过挂载的套接字连代理（[secure-deployment](https://code.claude.com/docs/en/agent-sdk/secure-deployment)）。
- **技能白名单不是隔离**：SDK 用 `skills` 选项限制可调用的技能，但文档明说，未列出的技能文件“仍在磁盘上，Read 和 Bash 仍能读到”（[agent-sdk/skills](https://code.claude.com/docs/en/agent-sdk/skills)）。要真正不可见，只能不放进环境。
- 模型看到的技能列表只含名称和描述，正文在触发后才读（渐进加载，[Agent Skills 概览](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)）。

### 2.3 OpenAI Agents SDK 沙箱 Agent 与 Codex

- 编排（控制面：循环、模型调用、工具路由、审批、恢复、运行状态）与计算（沙箱：文件、命令、包、挂载、快照）分开，目的是让秘密远离模型写的代码；`Manifest` 声明新会话的工作区内容，技能作为能力按 Agent 挂载，用 Git `ref` 钉版本（[sandboxes](https://developers.openai.com/api/docs/guides/agents/sandboxes.md)、[发布博客](https://openai.com/index/the-next-evolution-of-the-agents-sdk/)）。
- Codex CLI 在 Linux 上用 seccomp + Landlock 做命令级沙箱，`workspace-write` 默认关闭网络（[Codex 安全](https://developers.openai.com/codex/security)）。Claude Code 的命令沙箱在 Linux 上用 bubblewrap，网络经沙箱外代理按域名放行，子进程继承同一边界（[sandboxing](https://code.claude.com/docs/en/sandboxing)）。

### 2.4 基于 pi 的服务化 Agent

| 项目 | Agent 循环在哪 | 工具在哪执行 | 技能与配置 | 凭据 |
|---|---|---|---|---|
| pi 官方容器化文档 | 宿主 pi 进程 | Gondolin 扩展覆盖内置 `read/write/edit/bash/grep/find/ls`，送进本地微虚拟机，工作目录挂到 `/workspace`；未委托的扩展工具仍在宿主执行 | 未涉及 | 整个 pi 放进 Docker 时密钥进容器；OpenShell 方式密钥留在沙箱外，在 `inference.local` 注入（[containerization](https://unpkg.com/@earendil-works/pi-coding-agent@0.80.9/docs/containerization.md)） |
| OpenClaw | 网关进程，“始终在宿主” | “只有工具执行进沙箱”；按 Agent 配置沙箱，后端可选 Docker、Podman、SSH、OpenShell | 每个 Agent 单独配置沙箱与工具允许/拒绝 | 托管身份默认不进沙箱，开启时只读挂载并提示风险（[sandboxing](https://docs.openclaw.ai/gateway/sandboxing)）；运行时已从 pi 演化为自有实现（[pi](https://docs.openclaw.ai/pi)） |
| Oppi | 宿主：模型循环、认证、会话存储 | `read/bash/edit/write/ls/find/grep` 路由进 Gondolin 微虚拟机；挂载外的路径直接失败，不做重映射 | 工作区技能只读挂载，全局技能和全局 AGENTS.md 不暴露 | “模型调用在宿主，虚拟机不需要密钥”，只传模型名、会话 id 等非秘密值（[sandbox](https://cdn.jsdelivr.net/gh/duh17/oppi@main/docs/sandbox.md)） |
| Vercel AI SDK 的 Pi 适配 | 宿主 Node 进程 | 沙箱作为远程文件系统和 shell；文件工具有可读/可写根目录策略，但“bash 不受它限制”，靠沙箱本身 | 关闭文件系统扩展发现，只加载代码注入的扩展 | 凭据存储可换成数据库；多副本时关闭进程内重连（[ai-sdk Pi](https://ai-sdk.dev/providers/ai-sdk-harnesses/pi)） |
| 社区提案（反例） | 每次运行的微虚拟机内跑 pi-agent-core | 工具是调后端 API 的 HTTP 包装，在 `beforeToolCall` 检查范围 | — | 推理和工具留在厂商控制面（[discussion #3337](https://github.com/earendil-works/pi/discussions/3337)） |

pi 本身默认不做任何隔离；服务化项目除最后一个外，都是“宿主跑循环 + 把内置文件与命令工具改写为在沙箱执行”。pi SDK 的 `createAgentSession` 支持自定义 `resourceLoader`（技能来源由代码决定，不再按 `cwd` 扫描）和 `SessionManager.inMemory()`（会话由自己的数据库保存）（[sdk](https://unpkg.com/@earendil-works/pi-coding-agent@0.80.9/docs/sdk.md)）。DataAgent 的 Pi Cell 已经是独立 Node 进程、事件已写数据库，按这个模式改动最小：Cell 移到沙箱外，只把 Bash 和文件工具的执行改为送进沙箱。

### 2.5 共同结论

1. **编排在沙箱外，沙箱里只执行命令**；编排无状态，会话日志是唯一状态。
2. **Agent 定义带版本**，技能和 MCP 在定义里按名称（和版本）声明；会话钉住版本。
3. **凭据不进沙箱**，由代理或 vault 在出口注入。
4. **可见即存在**：环境里只放本会话可用的技能；名单限制不等于隔离。
5. **隔离一层**：容器/微虚拟机，或命令级系统沙箱，二选一，不叠加文本规则。

## 3. 现状（已核实）

一次任务：后端 → 沙箱 runner（容器，挂 docker socket）→ 每任务一个子容器 → 子容器内 `sandbox_task_main.py` 运行完整 Agent 循环（`_execute_task_stream_local`，Pi Cell 或 Claude Code）。

| 事实 | 现在由谁表达 | 与 2.5 的差距 |
|---|---|---|
| 启用哪些技能 | 系统提示“已启用 Skills”；Skill 工具白名单（`skill-loader.ts`）；子容器只挂启用目录（`sandbox_runner_main._build_skill_mounts`）；本地模式靠边界钩子文本检查 | 四处表达（违反 4）；Skill 工具只列名称不列描述 |
| 技能版本 | Agent 快照只存目录名（`agent_profile_service.build_agent_snapshot`） | 改共享目录即影响已发布 Agent 和进行中的会话（违反 2） |
| 读写范围 | 子容器挂载 + `boundary_policy` 文本规则（Python、TypeScript 两份实现，靠一致性用例对齐） | 两层（违反 5）；文本规则误拒 `cd`、heredoc |
| 凭据 | 模型密钥、MCP 配置随任务参数经 stdin 进入子容器，与 Bash 同处 | 违反 1、3 |
| 会话状态 | 事件表 `da_agent_sdk_record` + 每话题 `home/` | 两处状态（违反 1） |
| 运行配置 | 任务参数、环境变量（`DATAAGENT_ENABLED_SKILLS`、`DATAAGENT_ENABLED_SKILL_ROOTS`）、工作区 `.claude/` | 运行时文件混在工作区，Agent 会去翻 |

## 4. 目标结构

沿用 Managed Agents 的四个概念，DataAgent 内部实现：

| 概念 | DataAgent 中的落点 |
|---|---|
| Agent（带版本） | `agent_profile` 发布版本：提示词、模型、限额、`技能名@内容摘要`、MCP 服务 id；不可变 |
| Environment | 沙箱配置：解释器与预装包、网络放行主机；按 Agent 选择，不带版本 |
| Session | 话题：钉住 Agent 版本，持有工作区 `topics/<topic_id>/workspace/` |
| Events | `da_agent_sdk_record`：唯一状态，压缩也是一条事件 |

### 4.0 部署视图

```
                      ┌──────────────── dataagent-backend（控制面 + 编排）────────────────┐
 前端 ── HTTP/SSE ──▶ │ API：Agent 版本、技能注册表、MCP 注册表与密钥、会话               │
                      │ 每个进行中的轮次：一个 Pi Cell（Node 子进程，stdio）               │
                      │   · 调模型（持有模型密钥）                                          │
                      │   · MCP 工具：Cell 直连 MCP 服务（凭据由后端注入 Cell）  ──────────┼──▶ portal-mcp、OntoFoundry MCP
                      │   · Skill 工具：读本轮技能目录，返回 SKILL.md                       │
                      │   · Bash / Read / Write / Edit / Grep / Find / LS：转发 execute ───┼──┐
                      │ 事件写 MySQL（唯一状态）                                           │  │
                      └────────────────────────────────────────────────────────────────────┘  │
                                                                                               ▼
                      ┌──────────── dataagent-sandbox（执行面，无凭据，无 docker socket）────────┐
                      │ execute(session, op)：每个命令一个受限进程                               │
                      │   A：Landlock + seccomp；B：该会话的容器内执行                          │
                      │   可读写：topics/<topic>/workspace    只读：本轮技能、解释器              │
                      │   网络：无（平台能力走 MCP，由编排调用）                                  │
                      └──────────────────────────────────────────────────────────────────────────┘
 共享卷 runtime/：topics/<topic>/workspace/（两边挂载）、skills/<name>@<digest>/（后端写，沙箱只读）、runs/<run>/run.json（只后端）
```

与现在相比：Agent 循环从子容器回到后端；runner 换成只执行命令的沙箱服务，去掉 docker socket（现在子容器还共享 runner 的网络命名空间 `container:opendataworks-dataagent-sandbox-runner`，网络并未隔离）。

### 4.0.1 一轮的流程

1. 用户消息写成事件。后端按会话钉住的 Agent 版本生成 `run.json`，确认所需技能摘要已在 `skills/` 缓存中（没有则从注册表物化）。
2. 后端启动 Pi Cell，传入模型、提示词、工具定义、本轮技能列表（名称、描述、目录）和 MCP 连接；会话用 `SessionManager.inMemory()`，历史从事件重建。
3. Cell 调模型。工具调用分三类：Skill 在 Cell 内读技能目录；MCP 由 Cell 直接调用；文件与命令工具转成 `execute` 请求交给沙箱，结果作为工具输出返回，沙箱失败作为工具错误返回。
4. 每个事件经 stdio 回到后端写库，再推给前端。
5. 轮次结束，产物留在工作区 `output/`。后端重启时，进行中的轮次从事件恢复（重新起 Cell，接着最后一个事件）。

### 4.1 每轮运行规格（Agent 看不到）

编排在每轮开始生成 `runs/<run_id>/run.json`（工作区之外），只来自 Agent 版本和本轮请求：

```json
{
  "agent": {"id": "modeling", "version": 7},
  "model": {"provider": "deepseek", "name": "deepseek-chat", "context_window": 131072},
  "skills": [{"name": "ontology-discovery", "digest": "sha256:…", "description": "…"}],
  "mcp_servers": ["ontofoundry"],
  "environment": "python-data",
  "workspace": "topics/<topic_id>/workspace",
  "limits": {"max_turns": 60, "timeout_s": 1800}
}
```

| 路径 | 内容 | Agent 可见 |
|---|---|---|
| `runs/<run_id>/run.json` | 运行规格 | 否 |
| `skills/<name>@<digest>/` | 技能内容，按摘要从注册表物化，多轮共用；本轮用到哪些由 `run.json` 决定，沙箱只放行这几个目录 | 只读 |
| `topics/<topic_id>/workspace/` | `uploads/`、`output/`、业务中间文件 | 读写，工作目录 |

工作区里不再有 `.claude/`、`home/` 或任何运行配置。

### 4.2 技能：可见即存在

- 技能注册表按内容摘要存储；上传新内容生成新摘要，不覆盖旧的；Agent 版本钉摘要，升级技能要重新发布 Agent。
- Skill 工具只列 `run.json` 中的技能（名称和描述），沙箱也只放行这几个目录：没列的技能在工具里没有，在文件系统里也读不到。不再需要白名单拒绝逻辑和提示里的启用行。

### 4.3 隔离：一层

编排在沙箱外，只有命令进沙箱。两种实现，按部署条件二选一：

- **A. 命令级系统沙箱**（Codex CLI、Claude Code 的做法）：每条命令用 Landlock + seccomp（或 bubblewrap）包裹，工作区可读写，本轮技能和解释器只读，网络关闭。开销最低，不需要 docker socket。前提：内核 ≥ 5.13（Landlock）；bubblewrap 在容器内还需要用户命名空间。
- **B. 每会话一个容器**（Managed Agents、Agent SDK 一次性模式的做法）：编排通过 `execute` 把命令送进会话容器，容器 `--network none`、只读根文件系统、只挂工作区和本轮技能。需要容器管理权限，但编排不再在容器里。

两种都删除：`boundary_policy` 文本规则及其双实现、技能路径别名；A 还删除子容器和 docker socket。文件工具（Read/Write/Edit）由编排按解析符号链接后的真实路径判断是否在工作区或本轮技能目录内，规则只有这一条。

### 4.4 凭据

- 模型密钥、MCP 凭据只在编排；MCP 调用由编排发起，工具按 Agent 版本的 MCP id 过滤。
- 沙箱环境变量只放非秘密值（解释器、工作区路径）。
- 技能脚本需要调平台时（如 OntoFoundry 提案检查），改为平台 MCP 工具由 Agent 调用；不再把运行凭据文件放进工作区。

### 4.5 会话

- 事件表是唯一状态；编排崩溃或重启后按会话 id 从日志恢复。
- 验收目标（2026-10-26 用户确认）：DataAgent 重启不影响用户会话。等待中的问题卡片和计划卡片在重启后仍可回答，回答后接着原来的运行继续（卡片设计 PQ-06 并入本项）。
- 压缩写成一条事件，后续从“最后一次压缩 + 之后的事件”重建上下文。
- 删除每话题 `home/`。

## 5. 工具与报错

沿用现有约定：错误以“错误 [CODE]：原因。状态。下一步：命令”开头，提示以“提示 [CODE]”开头；命令可直接复制；超限截断并给出分页命令。

## 6. 删除与新增

| 删除 | 新增 |
|---|---|
| 系统提示的启用技能行、`DATAAGENT_ENABLED_SKILL*` 环境变量、Skill 工具白名单 | `run.json`；技能按摘要物化；Skill 工具按 `run.json` 列出名称与描述 |
| 共享技能目录直读 | 技能注册表内容摘要；Agent 版本钉摘要 |
| 子容器内的模型密钥与 MCP 配置；工作区里的运行凭据文件 | 编排侧 MCP 调用；平台检查改为 MCP 工具 |
| `boundary_policy` 文本规则、双实现、一致性用例、路径别名 | 文件工具真实路径根检查；命令沙箱（A）或会话容器 `execute`（B） |
| （A）每任务子容器、docker socket | — |
| 话题 `home/` | 压缩事件 |

## 7. 实施顺序

1. **运行规格与技能物化**：生成 `run.json`，按摘要物化技能，Skill 工具只列 `run.json` 中的技能并给出描述；删除提示启用行和白名单。不动隔离，先消除“四处表达”。
2. **技能版本**：注册表按摘要存储，Agent 发布钉摘要。
3. **编排移出沙箱**：Agent 循环回到后端，命令经 `execute` 进沙箱；MCP 由编排调用；平台检查改为 MCP 工具（OntoFoundry 侧同步改 `prepare_result.py`）。
4. **替换隔离**：上线 A 或 B，删除文本规则；选 A 时删除子容器。
5. **会话状态**：压缩事件化，删除 `home/`。

每步可独立发布，完成后跑一次建模和一次问数的真实运行验收。

## 8. 待确认的决定

| 编号 | 问题 | 推荐 | 备选 |
|---|---|---|---|
| SA-01 | 隔离方式 | A 命令级沙箱（内核满足时）：现场是单机部署，开销最低，去掉 docker socket | B 每会话容器 |
| SA-02 | 引擎 | 只保留 Pi 引擎，删除 Claude Code 引擎路径与 `home/` | 两个都保留，Claude Code 会话文件从日志重建 |
| SA-03 | 平台调用 | 平台能力做成 MCP 工具，脚本不持凭据 | 本轮签发的单用途令牌文件 |
| SA-04 | 技能升级 | 钉版本，升级需重新发布 Agent（Managed Agents 默认 `latest`，但我们的技能与平台契约强绑定） | 发布时可选“跟随最新” |

需要的信息：生产 DataAgent 宿主机内核版本（`uname -r`），以及 DataAgent 容器是否允许用户命名空间（决定 SA-01 用 Landlock 还是 bubblewrap）。
