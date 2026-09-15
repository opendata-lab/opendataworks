# 设置页加载性能与 MCP 可见性设计

**Date:** 2026-09-15
**Goal:** 消除 Skill 管理、模型管理、MCP 管理与欢迎页的加载延迟根因（连接风暴与事件循环阻塞），统一初次加载的骨架屏反馈，并在不泄露凭证的前提下把 MCP 菜单开放给普通用户。

## Scope

本设计覆盖：

- `dataagent/dataagent-backend`：`core/skill_admin_service.py`、`core/agent_profile_service.py`、`api/admin_routes.py` 的只读路径
- `dataagent/dataagent-frontend`：`views/settings/SkillStudio.vue`、`McpConfig.vue`、`DataAgentConfig.vue`、`router/index.js`、`views/settings/SettingsLayout.vue`
- MCP 列表端点的权限层级与敏感字段脱敏

本设计不覆盖：数据库连接池化改造、Alembic schema 变更、Skill 文件编辑与导入写路径的语义、评测页、Widget 接入页。

## Current State

实测环境：本机 Docker MySQL `127.0.0.1:3306`、schema `dataagent`、`.venv-py313`、backend `127.0.0.1:8899`、auth 关闭。

单次页面加载的后端开销（MySQL 在 localhost，已预热）：

| 页面 | 端点 | 耗时 | 新建 MySQL 连接 | 查询 |
|------|------|------|------|------|
| Skill 管理 | `GET /v1/dataagent/skills/documents` | **403 ms** | **255** | 255 |
| MCP 管理 | `GET /v1/dataagent/mcp/servers` | 11 ms | 1 | 1 |
| 模型管理 | `GET /v1/nl2sql-admin/settings` | 14 ms | 2 | 2 |
| 欢迎页 | `GET /v1/dataagent/agents` | 16 ms | 5 | 6 |

事件循环阻塞实测（`health` 单独 5 ms）：

| 场景 | 耗时 |
|------|------|
| `health` 与 Skill 请求并发 | **356 ms** |
| `mcp/servers` 与 Skill 请求并发 | **349 ms** |
| 3 个并发 Skill 请求的墙钟时间 | **1135 ms**（完全串行） |

`core/skill_admin_store.py` 与 `core/runtime_registry_store.py` 的 `_connect()` 每次调用都新建 `pymysql.connect()`，没有连接池。因此"连接数"等于完整的 TCP + 认证握手次数。

## Problem

### P1 Skill 列表的 N+1 连接风暴（主因）

`list_documents()` 对每个文档调用 `_document_api_payload()`，其中：

- `_is_skill_enabled(folder)` 在未传入 `skill_runtime` 时回落到 `_skill_runtime_from_current_settings()` → `current_settings_payload()`，而后者每次执行 `load_settings_record()` 与 `list_providers()` 两次独立连接；
- `_skill_description_from_front_matter(folder)` 每个文档都重新读取并解析同一个 `SKILL.md`。

当前磁盘有 84 个受管文档、8 个 Skill 目录。页面只渲染 8 行，却为 84 个文档各付一次完整设置读取：84 × 2 ≈ 170 次连接。

`reindex_documents_from_disk()` 再对每个文件调用一次 `store.get_document_by_path()`，叠加约 84 次连接。合计 255 次。

`_is_skill_enabled()` 本身已支持传入 `skill_runtime` 参数，调用点从未传值——这是一个未被使用的既有扩展点，而非需要新增的抽象。

### P2 阻塞 I/O 运行在 async 处理函数中（放大器）

`api/admin_routes.py` 的只读端点声明为 `async def`，但内部全部是同步阻塞的 `pymysql` 与文件 I/O。FastAPI 会在事件循环上直接执行 `async def`，因此 Skill 请求的 400 ms 阻塞期内，整个进程无法处理任何其他请求——包括 MCP 页、模型页、欢迎页与 SSE 对话流。

这解释了为什么后端仅需 11 ms 的 MCP 页也被感知为"慢"：`/settings` 默认重定向到 `/settings/skills`，用户随后切换页签时，请求排在仍在阻塞的 Skill 请求之后。

### P3 欢迎页把种子与回填放在读路径上

`list_agent_profiles()` 与 `get_agent_profile()` 都先调用 `bootstrap_default_agent_profile()`，后者每次执行 3 次内置 profile `SELECT`，再执行 `backfill_default_bindings()`——对 `da_agent_topic` 与 `da_agent_task` 各一条全表 `UPDATE ... WHERE agent_id IS NULL OR ...` 并提交。

这是一次性迁移语义，却运行在每次读取上。`main.py` 启动时已调用过一次。随着两张表增长，这两条写语句的代价只会上升，且每次对话请求都会触发。

### P4 模型页重复往返

`DataAgentConfig.vue` 的 `loadSettings()` 先 `await listProviders()`，再 `await getSettings()`，而 `getSettings()` 的响应已包含同一份 `_provider_catalog()`。两次串行往返取得完全相同的数据。

### P5 初次加载反馈不当

`.skill-table-wrapper` 只有 `min-width: 0`，没有 `min-height`。初次加载时容器内仅有一行分组标题，`v-loading` 遮罩高度约 20 px，转圈被压扁和裁切——这是"Skill 管理 loading 效果有点小问题"的成因。`.mcp-content` 同样缺少高度约束；`.provider-workbench` 因 `min-height: 640px` 反而正常。

同时分组标题在数据到达前就渲染 `已启用 0`，先显示一个错误计数再跳变。

### P6 列表请求重扫磁盘，而数据库本就是同步的

`list_documents()` 与 `get_document_detail()` 每次都先跑 `reindex_documents_from_disk()`：`rglob` 整棵 skill 树、读取并哈希每个受管文件。但这些内容在库里已经有了，且每条写路径都维护着两侧一致（见下方一致性模型），所以读请求重扫磁盘是纯冗余。

规范 skills 目录在部署里是挂载卷（`${DATAAGENT_SKILLS_DIR}:/app/.claude/skills`），对它做目录遍历的代价不保证稳定在本机实测的 3.6 ms。

#### 磁盘与数据库的一致性模型（现状，已核实）

同步方向只有磁盘 → 数据库：`reindex_documents_from_disk()` 比对文件 sha256 与 `da_skill_document.current_hash`，不一致就把文件内容写入库。没有反向同步，除了两条编辑路径的双写。

| 触发时机 | 磁盘 | 数据库 |
|---|---|---|
| 启动 `main.py:105` | 读 | 写（全量扫描 + 哈希比对） |
| 界面编辑 `save_document_content` | 写 `write_skill_file` | 写 `store.save_document` |
| 版本回滚 `rollback_document` | 写 `write_skill_file` | 写 `store.save_document` |
| 导入 Skill `import_skill_from_zip` | 写（解压） | 写（随后 reindex） |
| 卸载 Skill `uninstall_skill` | `shutil.rmtree` | 逐行 `delete_document_by_path` |

存在性以磁盘为准：reindex 会删掉「库里有、磁盘上已不是受管文件」的行。

智能体运行时**不读这棵树**：`prepare_topic_workspace()` 把每个启用的 skill 目录 `copytree` 到 `topic_<id>/workspace/.claude/skills/<folder>`，按两棵树的最新 mtime 判断副本是否过期。`agent_runtime.py` 只从 discovery root 拼路径字符串。规范目录今天只有一个写入方，就是 `skill_admin_service` 的上述四条路径。

### P7 MCP 菜单对普通用户不可见，且载荷含凭证

路由与菜单项都带 `adminOnly: true`，`GET /mcp/servers` 挂在 `skills_router`（`require_admin`）。

但 MCP 行包含 `headers` 与 `env`。当前实例返回：

```json
{"server_id":"portal","headers":{"X-Portal-MCP-Token":"odw-portal-mcp-token"}}
```

`headers` 承载 http/sse 服务的 bearer 凭证，`env` 承载 stdio 服务的 API key。直接把现有端点降权到普通用户会造成凭证泄露。

## Design

### D1 单次请求内共享设置与描述

`list_documents()` 解析一次运行时配置与 folder→description 映射，向下传递：

```python
def list_documents() -> list[dict[str, Any]]:
    reindex_documents_from_disk()
    skill_runtime = _skill_runtime_from_current_settings()
    description_cache: dict[str, str] = {}
    documents = [
        _document_api_payload(item, skill_runtime=skill_runtime, description_cache=description_cache)
        for item in get_skill_admin_store().list_documents()
    ]
```

`_document_api_payload()` 增加两个可选关键字参数，缺省时行为与当前完全一致，其他调用点无需改动。描述缓存按 folder 命中，同一个 `SKILL.md` 每次请求只读一次。

预期：170 次连接 → 2 次；84 次 front matter 解析 → 8 次。

### D2 reindex 用一次批量读取替代逐文件查询

`reindex_documents_from_disk()` 已经在删除阶段调用 `store.list_documents()`。将该结果构建成 `relative_path -> document` 映射并复用于哈希比对，取代循环内的 `store.get_document_by_path()`。

`_migrate_document_paths_to_discovery_root()` 同样复用该映射，避免重复整表读取。

预期：84 次连接 → 0 次额外连接。

### D3 只读端点改为同步处理函数

把 `api/admin_routes.py` 中不含 `await` 的阻塞端点由 `async def` 改为 `def`。FastAPI 对同步处理函数自动使用线程池执行，事件循环不再被占用。

这是最小且惯用的修法，不需要在每个端点手写 `anyio.to_thread.run_sync`。含真实 `await` 的端点（如 `create_model_detection`）保持 `async def`。

判定规则：函数体不出现 `await`，且调用链为同步阻塞 I/O，才转为 `def`。

### D4 内置 profile 种子每进程一次

`bootstrap_default_agent_profile()` 采用与 `init_schema()` 相同的一次性语义：模块级标志加锁，进程内只执行一次，并缓存返回的默认 profile 快照。

`list_agent_profiles()` 仍每次从 `store.list_profiles()` 取最新数据，新建智能体照常可见；只有种子与历史回填不再重复。

预期：欢迎页 5 次连接 → 1 次，并去掉每次读取的两条全表 UPDATE。

### D5 模型页单次往返

删除 `loadSettings()` 中的 `listProviders()` 预取，直接使用 `getSettings()` 返回的 `providers`。

两者同源于 `_provider_catalog()`，该预取与其"失败则继续"的分支属于无环境差异支撑的重复兜底，按仓库规则（保持兜底最小、单层）移除。`listProviders()` 本身保留，供其他调用点使用。

### D6 初次加载用骨架屏，刷新用遮罩

区分两种加载态：

- 初次加载（尚无数据）：渲染 `el-skeleton` 行，条数与列表结构呼应，标题计数与空状态都不渲染；
- 刷新（已有数据）：保留 `v-loading` 遮罩，数据留在原位，避免内容跳动。

同时给列表容器补 `min-height`，遮罩不再被压扁。按仓库的同级面板一致性规则，Skill 管理与 MCP 管理采用同一处理；模型管理已有 `min-height: 640px`，仅对齐骨架屏语义。

### D7 读路径只读数据库

`list_documents()` 与 `get_document_detail()` 不再调用 `reindex_documents_from_disk()`，整个请求路径不触碰磁盘。

Skill 描述改为从库中已存的 `SKILL.md` 内容解析：新增 `store.list_skill_manifest_contents()`，一条 `WHERE file_name = 'SKILL.md'` 的有界查询取回那几行内容，`_front_matter_value_from_text()` 就地解析。`list_documents()` 本身仍不返回 `current_content`，列表响应体积不变。

磁盘归两类使用者：智能体运行时（消费 per-topic 副本）和显式管理动作（启动索引、导入、编辑、回滚、卸载）。

**不给列表端点加 `refresh` 查询参数。** 曾考虑让刷新按钮触发重扫，但终端用户不知道也不该关心磁盘与数据库的区别，刷新按钮的语义就是「重新拉一次列表」。应用外的磁盘改动由启动索引和写路径覆盖；挂载卷被运维热更新这种场景，代价是重启，不值得为此在 API 上留一个「谁都不该用」的开关。

`reindex_documents_from_disk()` 保留为公开函数，仍带 mtime/size 签名短路与按签名跳过未变文件，供启动与写路径使用。

### D8 MCP 读写分层与脱敏

端点分层：

- `GET /v1/dataagent/mcp/servers` 移到 `user_router`（`require_user`），登录用户可读；
- `POST` / `PATCH` / `DELETE` / `import` 保留在 `skills_router`（`require_admin`）。

脱敏契约：非 admin 调用者拿到的每一行中，`headers` 与 `env` 只保留键名、值一律置为空串，`command` 与 `args` 置空。沿用设置端点既有做法——`anthropic_api_key`、`mysql_password` 等对前端一律返回空串，前端以"已设置"布尔量展示。

为此每行增加两个布尔摘要 `headers_set`、`env_set`，使普通用户能看到"该服务已配置凭证"而拿不到凭证本身。

前端：移除路由与菜单项的 `adminOnly`，在 `McpConfig.vue` 引入 `canManage = authStore.isAdmin`，按 `SkillStudio.vue` 的既有模式隐藏新增/编辑/删除/导入入口与启用开关。

## Interfaces

| 接口 | 变更 | 兼容性 |
|------|------|--------|
| `GET /v1/dataagent/skills/documents` | 响应结构不变；不再重扫磁盘，应用外的改动需启动或写路径触发 reindex | 结构兼容，刷新语义收窄 |
| `GET /v1/dataagent/skills/documents/{id}` | 同上 | 结构兼容 |
| `GET /v1/dataagent/mcp/servers` | 权限 admin → 登录用户；非 admin 响应脱敏；新增 `headers_set`、`env_set` | admin 视图字段不变，新增字段向后兼容 |
| `POST/PATCH/DELETE /v1/dataagent/mcp/servers*` | 不变，仍限 admin | 兼容 |
| `GET /v1/nl2sql-admin/settings` | 仅性能 | 兼容 |
| `GET /v1/dataagent/agents` | 仅性能 | 兼容 |

前端路由 `/settings/mcp` 由 admin 专属变为登录用户可达。

## Tradeoffs

**同步处理函数 vs 连接池。** 真正的根治是给两个 store 引入连接池，但那会改动所有持久化调用点，风险远超本次范围。D1/D2 把连接数从 255 降到个位数后，池化的收益变得次要；D3 用一行改动换回并发能力。池化留作后续独立变更。

**进程内一次性种子 vs 每次读取。** 若外部直接删库中的内置 profile 行，进程不会自动重建，需重启。这与 `init_schema()` 已有的一次性语义一致，且内置 profile 属于部署期资产，取舍可接受。

**脱敏 vs 直接开放。** 也可以为普通用户另建一个精简端点，但那会产生两套需同步维护的 MCP 读契约。按身份脱敏同一端点更贴合仓库"避免跨层重复契约"的规则。

**骨架屏 vs 纯遮罩。** 骨架屏增加少量模板，但初次加载不再出现被裁切的转圈与 `已启用 0` 的错误计数跳变，这正是本次要修的观感问题。

## Verification

- 后端：针对 `list_documents()` 的连接计数回归测试（断言与文档数无关）、读路径零磁盘访问测试、reindex 行为不变测试、`bootstrap` 一次性测试、MCP 脱敏测试；
- 前端：`SkillStudio` / `McpConfig` 骨架屏与 `canManage` 门控测试，路由与菜单可见性测试更新；
- 端到端：本机 MySQL + Redis + backend + 前端 dev server，实测四个页面的连接数与并发阻塞指标恢复。

### 实测结果

环境：MySQL `127.0.0.1:3306`（Docker `data-portal-mysql`）、schema `dataagent`、Redis `127.0.0.1:6379`、`.venv-py313`（Python 3.13.4）、backend `127.0.0.1:8900`、前端 dev server `localhost:3001`、auth 关闭、未使用真实模型凭证。

| 指标 | 改前 | 改后 |
|------|------|------|
| Skill 列表耗时 / 新建连接 | 532 ms / 255 | **16 ms / 3** |
| Skill 请求并发时 `health` | 356 ms | **3 ms** |
| Skill 请求并发时 `mcp/servers` | 349 ms | **6 ms** |
| 3 个并发 Skill 请求墙钟 | 1135 ms（串行） | **26 ms**（并行） |
| 欢迎页 `agents` | 5 连接 + 2 条全表 UPDATE / 次 | 1 连接，UPDATE 每进程一次 |
| 模型页往返 | 2 次串行 | 1 次 |

测试：后端 `pytest` 725 通过；前端 settings / router / api 共 110 通过。

`tests/test_pi_runtime_e2e.py::test_real_cell_completes_the_protocol_round_trip` 失败，已用 `git stash` 确认改动前即失败（需要编译好的 Pi runtime），与本次变更无关。

未覆盖：auth 启用下的普通用户手测。本机 auth 关闭时 `isAdmin` 恒为真，MCP 脱敏与写入口隐藏仅由后端单测与前端 `canManage` 单测覆盖，未做真实登录态的端到端验证。
