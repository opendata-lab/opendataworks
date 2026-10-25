# DataAgent 插件管理与导入设计

日期：2026-10-22，2026-10-23 按 codex 评审修订。状态：**暂缓**。第二轮评审仍需修改后定稿，待办和遗留问题见 [docs/TODO.md](../TODO.md#插件管理与导入暂缓后续专门实施)，后续专门实施。实施计划见[对应计划](../plans/2026-10-22-dataagent-plugin-management-plan.md)。

## 1. 现状

DataAgent 目前分别管理两类扩展，二者之间没有"安装单元"的概念：

| 能力 | 现有实现 | 入口 |
| --- | --- | --- |
| Skill | 落盘到 `SKILLS_ROOT_DIR/<folder>`，`da_skill_document(_version)` 建索引和版本；支持 zip 导入/导出/卸载/启停 | `POST /api/v1/dataagent/skills/imports` 等，`SkillStudio.vue` |
| MCP | `da_mcp_server`，`source=configured|plugin`，`transport=stdio|http|sse`；支持 `mcpServers` JSON 批量导入和连通性探测 | `/api/v1/dataagent/mcp/servers*`，`McpConfig.vue` |
| Agent 配置 | `agent_profile` 和 `agent_draft` 分别勾选 skill folder 与 MCP server id，发布时生成快照 | `/api/v1/dataagent/agents*` |

问题：

- 一个能力包（例如"某业务域分析"）往往同时包含若干 skill 和一个 MCP 服务，现在需要分两处导入，升级和卸载时也只能逐个处理，无法知道哪些部件属于同一个包、哪个版本。
- 社区和 Claude Code 生态的插件（`.claude-plugin/plugin.json`）无法直接导入。
- `da_mcp_server.source=plugin` 目前只用于内置 Portal MCP，"plugin"这个词还没有对应的实体。

已核实的现有行为，本设计依赖或需要修正这些行为：

- agent 快照只保存 skill folder 和 MCP id，不保存内容和版本（`agent_profile_service.build_agent_snapshot`）。
- 运行时按快照的 skill folder 直接拼出路径，**不检查 skill 是否全局启用**（`agent_runtime.resolve_agent_skill_runtime`，沙箱的 `sandbox_runner_main` 同理）；MCP 则会检查 `da_mcp_server.enabled`。
- 现有 zip 解压函数会拒绝路径穿越和符号链接，但**没有大小和文件数限制**。
- `require_admin` 在认证关闭时直接放行。
- skill 写入接口（文档编辑、版本回滚、覆盖导入、卸载、启停）只保护内置 skill。

## 2. 目标与边界

目标：

1. 以**插件**作为安装、升级、启停、卸载和导出的单元，一个插件可以带多个 skill 和 MCP 服务。
2. 支持**明确列出的 Claude Code 插件格式子集**（§4），能直接导入符合该子集的现有插件；自研插件按同一结构打包。超出子集的声明逐项报告，不静默忽略。
3. 能力以生产引擎 Pi（`pi_agent_core`）为基准：Pi 不支持的部件在导入报告里明确标出，不做模拟实现。
4. 运行时不感知"插件"：插件安装后展开为现有的 skill 目录和 MCP 行。运行时只需要一处**通用**修正：快照里的 skill 要与当前启用状态取交集（§7），这不涉及任何插件或特定 skill 的逻辑。

不做：

- 插件市场、在线评分、自动更新。
- 插件携带 skill 目录以外的可执行文件，包括依赖 `${CLAUDE_PLUGIN_ROOT}` 的 MCP（§4）。
- commands、子代理、workflow、hooks：等运行时迁到 `pi-coding-agent` SDK 后再接入，见 §3。
- 插件版本固定。升级**立即生效**（§6）。

## 3. Pi 引擎能力核对

运行时已升级到 `@earendil-works/pi-agent-core@1.4.0`（见 [Pi 1.4.0 升级设计](2026-10-22-pi-runtime-1-4-upgrade-design.md)）。Pi 有两层：

- **核心库** `pi-agent-core` / `pi-ai`：提供 `Agent` 和工具调用前后的钩子。我们的 `dataagent-runtime-pi` 只用了这一层，skills 和 MCP 都是自己实现的（`src/skills/skill-loader.ts`、`src/mcp/portal-mcp-client.ts`）。
- **`pi-coding-agent` SDK**（`createAgentSession` + `DefaultResourceLoader`）：在核心库之上提供扩展系统、skills 加载、prompt templates、MCP 扩展、Pi 包（`pi install npm:/git:`），以及丰富的扩展事件（`tool_call`、`tool_result`、`input`、`before_agent_start`、`turn_end` 等）。子代理由官方示例扩展 `examples/extensions/subagent` 提供，它的 `prompts/` 用命令模板串联多个子代理，形成 workflow（如 scout → planner → worker）。

| 插件部件（Claude Code 结构） | 当前运行时（只用核心库） | 迁到 `pi-coding-agent` SDK 后 | 本设计的阶段 |
| --- | --- | --- | --- |
| `skills/<name>/SKILL.md` | 支持（自研 `Skill` 工具） | 原生支持 | **第一阶段** |
| `.mcp.json`（stdio/http/sse） | 支持（自研 MCP 客户端，三种传输） | 官方 MCP 扩展，文档列出 stdio 和 Streamable HTTP；旧 SSE 不能假定等价支持 | **第一阶段** |
| `commands/*.md` | 不支持 | 原生命令模板 | 依赖 SDK 迁移 |
| `agents/*.md`（子代理） | 不支持 | 子代理扩展，但示例会启动独立的 `pi` 进程，需要重新接入工作区边界、取消和事件记录，不能直接兼容 Claude 的子代理定义 | 依赖 SDK 迁移 |
| workflow（命令模板串联子代理） | 不支持 | 子代理扩展加命令模板，前提同上 | 依赖 SDK 迁移 |
| `hooks/hooks.json` | 不支持 | 扩展事件可以承载，但 Claude Code hooks 是外部 shell 命令，需要写一个适配扩展 | 依赖 SDK 迁移，并另做安全评审 |

结论：第一阶段只做 skill 和 MCP，这部分不依赖运行时选型。commands、子代理、workflow、hooks 不在 DataAgent 里自研，统一等运行时迁到 `pi-coding-agent` SDK 后通过扩展提供。那次迁移需要重写 `kernel/cell.ts`，把工作区边界检查、结果折叠、上下文压缩和工具结果注册改写成扩展事件，并关闭 SDK 默认的编码工具、会话文件、项目信任和 `~/.pi` 配置，因此单独立项设计。在此之前，导入时遇到这些部件就登记为"已忽略"，报告中写明"依赖运行时迁移"。

Claude SDK 引擎（`DATAAGENT_RUNTIME_KIND=claude_code`）本身能加载插件目录。但为保证两个引擎行为一致，插件同样展开为 skill 和 MCP，不把插件目录直接交给 SDK。

## 4. 插件包格式（支持的子集）

```text
<plugin-root>/
  .claude-plugin/plugin.json   必需，只有 name 必填
  skills/<skill>/SKILL.md      可选，可多个，可带 reference/、scripts/
  .mcp.json                    可选：{"mcpServers": {...}}
  commands/ agents/ hooks/     登记为"已忽略（依赖运行时迁移）"，不安装
```

- `plugin.json`：
  - `name` 必填，匹配 `^[a-z0-9][a-z0-9-]{0,47}$`。长度留出余量，保证 `<plugin_id>__<skill>` 加上文件相对路径不超过 `da_skill_document.relative_path` 的 `VARCHAR(255)`，超出时预览报错；
  - `version`、`description`、`author` 可选。缺少 `version` 时，用包内容的 sha256 前 12 位作为版本标识，满足"升级时版本必须变化"的规则；
  - manifest 中自定义的 skill 目录、内联 `mcpServers`、或指向其他 MCP 配置文件的声明，第一阶段**不支持**，在报告中逐项列为"未支持的声明"，并拒绝安装，避免装出一个缺部件的插件。
- `.mcp.json`：
  - 包里的 `server_id`、`source`、`plugin_id` 等归属字段一律忽略，由安装器生成（§5）；
  - **出现 `${CLAUDE_PLUGIN_ROOT}` 的服务直接拒绝**（已定）。第一阶段只支持远程 http/sse MCP，以及调用镜像中已有命令（如 `npx`、`uvx`）的 stdio MCP；
  - 其余 `${VAR}` 视为**安装参数**：预览时列出来由管理员填写，值不写回插件目录，也不随导出带出。
- 也接受 `.claude-plugin/marketplace.json` 形式的多插件 zip，预览时选择其中一个安装。只接受包内的相对路径 source，并校验解析后的路径仍在包内；远程 source 拒绝。

## 5. 数据模型

新增 Alembic 迁移（session 库 `dataagent`），所有新表显式使用 `COLLATE=utf8mb4_unicode_ci`，与现有表一致：

```text
da_plugin
  plugin_id          VARCHAR(48) PK      = plugin.json name
  version            VARCHAR(64)
  display_name / description / author
  status             enabled | disabled
  source_ref         原始文件名
  package_sha256     原始包 sha256
  artifact_path      原始包在插件仓库中的位置（不含安装参数实际值）
  manifest_json      规范化后的 plugin.json
  components_json    [{kind: skill|mcp, original_name, installed_ref, enabled}]
  rewrite_json       逐文件的前缀改写记录 [{file, offset, before, after}]
  params_json        安装参数的绑定位置 [{name, server, field, secret}]，不含值
  ignored_json       [{kind, path, reason}]
  installed_at / updated_at / installed_by

da_plugin_operation
  op_id              VARCHAR(64) PK
  plugin_id / kind   install | upgrade | uninstall
  state              staged | swapped | committed | done | rolled_back
  backup_path        被替换目录的备份位置
  detail_json / created_at / updated_at

da_mcp_server  新增可空列 plugin_id
```

- **插件仓库**：新增配置 `DATAAGENT_PLUGIN_STORE_DIR`，放原始包、预览上传、暂存和备份，**位于 skill 扫描根目录之外**，避免暂存文件被 skill 索引扫到。多副本部署时，它和 `SKILLS_ROOT_DIR` 都必须是共享存储；这一点作为部署前提写进部署文档。
- **skill**：插件内 skill 落盘为 `<plugin_id>__<skill>`，复用 `da_skill_document` 索引和版本历史。列表接口中这类 skill 返回 `source=plugin`、`plugin_id`、`editable=false`。
- **MCP**：安装器按 `plg_<plugin_id>__<server名>` 生成 `server_id`（只保留 `[A-Za-z0-9._-]`），生成后同时检查 `server_id` 和 `name` 是否冲突；`portal` 保留不可用。行写入 `source=plugin`、`plugin_id=<id>`。内置 Portal MCP 是 `source=plugin` 且 `plugin_id` 为空，界面上继续作为"内置"单独展示，不链接到插件详情。
- **安装参数**：实际值只写入对应 MCP 行的 headers、env、args 或 url（沿用现有的存储和脱敏）；`params_json` 只记录参数绑定在哪个字段，用于后续轮换凭据。

## 6. 生命周期

### 6.1 一致性机制

文件系统、多张表和多副本之间无法做成单个事务，所以用"暂存、交换、提交、可恢复"代替"一个锁加上逆序回滚"：

1. **跨副本互斥**：所有插件写操作，以及普通 skill 的写入口（导入、保存、回滚、卸载、启停），都要先拿 MySQL 命名锁 `GET_LOCK('dataagent_skill_registry', 30)`。各副本共享同一个库，所以这个锁对多副本有效。
2. **暂存**：新版本的 skill 目录先完整写到 `PLUGIN_STORE_DIR/staging/<op_id>/`，并在 `da_plugin_operation` 中记为 `staged`。
3. **交换**：把将被替换的目录 `rename` 到 `PLUGIN_STORE_DIR/backup/<op_id>/`，再把暂存目录 `rename` 进 `SKILLS_ROOT_DIR`，记为 `swapped`。同一文件系统内的 `rename` 对单个目录是原子的。
4. **提交**：在**一个数据库事务**里写 `da_plugin`、MCP 行、settings 中的 `skill_runtime`，记为 `committed`。为此需要给 `runtime_registry_store` 和 settings 持久化增加"使用调用方传入的连接"的写法，不能沿用现在各自独立提交的方法。
5. **索引**：只对本插件涉及的 folder 做**限定范围的 reindex**。不用全量 reindex，因为全量 reindex 会删除磁盘上暂时缺失文件的索引，并级联删除它们的版本历史。完成后记为 `done`，清理备份。
6. **恢复**：进程启动时扫描未完成的操作。`staged` 直接丢弃暂存；`swapped` 但未 `committed` 的从备份还原目录；已 `committed` 的补做索引后标记为 `done`。

### 6.2 操作

| 操作 | 行为 |
| --- | --- |
| 预览 `POST /plugins/previews` | 上传的包写入 `PLUGIN_STORE_DIR/previews/<preview_id>.zip`（共享存储，所以任何副本都能完成安装；TTL 30 分钟，总量有上限）。**边解压边检查**大小和文件数限制（§8），解析出部件清单、已忽略项、未支持的声明、需填写的安装参数、前缀改写差异、stdio 命令、与已安装版本的差异，以及会受影响的 agent（同时扫描 `agent_profile` 和 `agent_draft`） |
| 安装 `POST /plugins` | 提交 `preview_id`、安装参数，以及 stdio 确认令牌（§8）。服务端**重新校验** checksum、当前已安装版本和冲突，然后按 §6.1 执行。新装插件和其中的 MCP 行**强制为 disabled**，覆盖 `normalize_mcp_server` 的默认值 `enabled=true` |
| 启停 `PATCH /plugins/{id}` | 级联修改插件内 skill 的 `skill_runtime.enabled` 和 MCP 行的 `enabled`。停用前**整体校验**：如果会导致全局一个启用的 skill 都不剩（现有规则禁止），直接拒绝，不做部分停用。启用时探测 MCP，结果记录在插件详情中，并标注探测所在的环境（§7） |
| 升级 | 重新预览并安装同名插件。**立即生效（已定）**：所有引用它的 agent 下一次运行就会用新版本，预览会列出这些 agent。升级保留插件当前的启停状态，新增的部件继承插件的状态。被删除的部件如果仍被引用，需要确认；确认后从 `agent_profile` 和 `agent_draft` 中移除引用，草稿的 `revision` 递增并清除旧的预览关联 |
| 轮换凭据 `PATCH /plugins/{id}/params` | 按 `params_json` 更新 MCP 行中对应的字段，不改变插件版本。替换后重新执行 MCP 规范化校验 |
| 卸载 `DELETE /plugins/{id}` | 按 §6.1 把目录移入备份，删除插件行、MCP 行、索引和设置，处理引用的方式同升级 |
| 导出 `GET /plugins/{id}/export` | **直接返回插件仓库里保存的原始包**：原始的 skill 名、带 `${VAR}` 占位符的 `.mcp.json`、被忽略的部件都保留原样。不对已改写的文件做反向替换 |

冲突规则：插件里的 skill folder、MCP `server_id` 或 MCP `name`，与内置项、手工配置项或其他插件重复时，预览直接报错，不覆盖、不自动改名。

### 6.3 后端归属保护

属于插件的 skill 和 MCP 在**后端所有写入口**都拒绝单独修改，并返回 409 和提示"属于插件 X，请在插件页操作"：

- skill：文档保存、版本回滚、覆盖导入同名 folder、卸载、运行时启停；
- MCP：更新、删除、通过 `mcpServers` 导入覆盖同名服务。

前端隐藏入口只是辅助，不能代替后端校验。

## 7. 与 Agent 配置和运行时的关系

- **启用状态取交集（通用修正）**：`resolve_agent_skill_runtime` 和沙箱的对应逻辑改为：快照中的 skill folder 必须同时满足"全局启用"且"目录存在"，否则从本次运行中剔除并记 warning。这样插件停用后，已绑定它的 agent 也不会再加载其中的 skill，与 MCP 的行为对齐。这是对**所有** skill 的行为变化：普通 skill 被停用，也会对已发布的 agent 生效，需要写进发布说明。正在运行的任务不受影响，下一次运行时生效。
- **升级语义**：快照只引用部件 id，插件升级立即影响所有引用它的 agent（已定，§6.2）。这与现在手工覆盖导入 skill 的行为一致。
- **agent 配置**：仍然勾选具体的 skill folder 和 MCP server id。`/agents/capabilities` 增加 `plugins` 分组，skill 和 MCP 的响应模型增加 `plugin_id`，需要同步修改 `models/schemas.py`，否则会被响应模型过滤掉。前端提供"选中整个插件"的快捷操作。
- **Skill 名称**：传给 Pi 的 skill 标识就是 folder（`task_executor` 以 folder 作为 `name`），`skill-loader.ts` 不解析 front matter。所以安装后，`Skill` 工具里的调用标识是 `<plugin_id>__<skill>`；front matter 里的 `name` 只用于界面展示。前缀改写（下一条）也覆盖包内对 `Skill` 名称的规范引用。插件路径不复用普通 skill 导入时"目录名必须等于 front matter `name`"的校验。
- **folder 前缀（已定）**：插件内 skill 一律落盘为 `<plugin_id>__<skill>`。安装时改写：
  - 范围：只限本插件 skill 目录下的文本文件（`.md`、`.py`、`.sh`、`.json`、`.yaml`、`.yml`）；
  - 规则：只替换完整路径片段 `${SKILLS_ROOT_DIR}/<skill>/`、`$SKILLS_ROOT_DIR/<skill>/`，以及 `Skill` 调用中的 `<skill>`，其中 `<skill>` 必须是**本插件内**的 skill 名。原文中已经带 `<plugin_id>__` 前缀的不再处理；
  - 不做猜测：相对路径、写死的绝对路径、引用其他插件或内置 skill 的路径都不改，在预览中列为警告；
  - 每处改写都记入 `rewrite_json`，预览逐文件展示差异，确认后才写入。导出用的是原始包，不依赖反向替换。
- **MCP 工具名冲突**：Pi 直接使用 MCP 返回的工具名注册，没有按服务加命名空间。启用插件时探测工具列表：与内置工具（`Skill`、`Bash`、`Read`、`LS` 等）同名的直接拒绝启用；agent 发布时校验所选的多个 MCP 之间没有重名工具，有重名则拒绝发布。是否改成按服务加命名空间，另行设计。
- **运行时路径**：`resolve_enabled_skill_runtime()` 和 `resolve_runtime_mcp_servers()` 只读 skill 设置和 `da_mcp_server`，插件只在写入侧展开。共享模块中不出现插件或具体 skill 的逻辑，符合 Intelligent Query 模块规则。
- **沙箱模式**：插件 skill 与普通 skill 处在同一个 `SKILLS_ROOT_DIR`，沿用现有的 live skills 同步。stdio MCP 在子容器内启动，但 `detect_mcp_server` 在管理后端进程里执行，**不能证明子容器里的命令和依赖可用**。所以探测结果要标注"探测环境：管理后端"，沙箱部署以子容器冒烟为准（§10）。

## 8. 安全

- **权限**：导入、升级、轮换凭据、卸载沿用 `skills_router` 的 `require_admin`。认证关闭的部署**照常允许导入（已定）**，与现有 skill 导入一致。部署文档要写明：这种部署下任何能访问管理接口的人都能安装插件，并通过 stdio MCP 在服务端执行命令。
- **stdio 命令确认**：预览时为每个 stdio 服务计算 `confirm_token = sha256(参数替换后的最终 command、args 和 env 的键名，加上 package_sha256)`。安装时必须回传这些令牌，后端重新计算比对，配置或包有任何变化就要求重新确认。命令以 argv 列表启动，不经过 shell，安装参数替换后不做二次展开。
- **解压限制**：现有解压函数没有大小限制，插件解压器**新增**：包 20MB，解压后总量 50MB，单文件 5MB，文件数 2000（均可配置）。在流式复制时累计检查，超限立即中止，不是解压完再检查。拒绝符号链接和路径穿越的逻辑沿用现有实现。
- **范围**：不安装 hooks、agents、commands，以及 skill 目录以外的任何文件。skill 自带的 `scripts/` 与现有 skill 导入的风险相同，不额外放宽。

## 9. 前端

在 `dataagent-frontend/src/views/settings` 新增"插件"页，与 `SkillStudio.vue`、`McpConfig.vue` 并列：

- 列表：名称、版本、状态、部件数、已忽略项数。
- 导入：上传 zip → 预览报告（部件、已忽略项、未支持的声明、stdio 命令及确认、安装参数表单、改写差异、升级差异、受影响的 agent）→ 确认安装。
- 详情：部件列表，可跳转到对应的 skill 详情或 MCP 行；启停、升级、轮换凭据、卸载、导出；展示最近一次 MCP 探测结果及探测环境。
- Skill 和 MCP 页上，属于插件的条目显示插件标签，编辑和删除入口改为跳转到插件页；内置 Portal MCP 继续显示为"内置"。

## 10. 验证、发布与取舍

验证：

- 单元测试：manifest 子集解析与未支持声明、版本标识、变量替换、`${CLAUDE_PLUGIN_ROOT}` 拒绝、ID 生成与冲突、前缀改写（只改规范写法，已带前缀的不重复处理，外部引用只出警告）、流式解压限制、stdio 确认令牌、工具名冲突。
- 一致性测试：§6.1 每个阶段注入失败，以及模拟进程中断后启动恢复；并发升级与卸载；限定范围的 reindex 不影响其他 skill 的版本历史。
- 归属保护测试：通过旧的 skill 和 MCP 接口修改插件部件，全部返回 409。
- 契约测试：插件安装后，`resolve_enabled_skill_runtime` 和 `resolve_runtime_mcp_servers` 的输出与手工导入同样部件时一致；插件停用后，已发布 agent 的运行时不再包含其 skill 和 MCP。
- 导出测试：导出后重新导入得到相同的部件；导出包中不含安装参数的实际值。
- 双副本测试：在副本 A 预览、在副本 B 安装。
- 端到端冒烟（Pi 引擎，`agent_opendataworks` 加一个测试插件，本地和沙箱子容器各跑一次）：安装 → 启用 → agent 勾选并发布 → 提交真实任务，确认插件 skill 被 `Skill` 工具加载、插件 MCP 工具被调用，SSE 事件流正常结束、终态和最终消息落库，并通过 `da_agent_sdk_record.engine_kind=pi_agent_core` 确认实际运行的引擎；随后停用插件，再跑一次，确认部件不再出现。

发布与回退：

- 发布顺序：迁移 → 后端 → 前端。没有安装插件时，除 §7 的启用状态取交集外，行为与现在一致。
- 回退前先**停用或卸载所有插件**：旧版后端仍会读取展开后的 skill 和 MCP，但没有归属保护。新表可以保留，旧代码不读它们。

取舍：

- 插件展开为现有部件，而不是新建一套"插件运行时"。好处是两个引擎行为一致、运行时只有一处通用修正；代价是 skill folder 带前缀、需要改写包内引用，并为安装引入暂存和恢复机制。
- 升级立即生效，不做版本固定。实现简单，与现有 skill 行为一致；代价是一次有问题的升级会立刻影响所有引用它的 agent，所以由预览列出受影响的 agent。
- 兼容 Claude Code 格式但只支持其子集，不承诺"Claude Code 插件全部能用"，报告中如实标出忽略项和未支持的声明。
- commands、子代理、workflow、hooks 不在当前运行时里自研，等 `pi-coding-agent` SDK 迁移后通过扩展获得，避免两套实现。
