# Widget 接入白名单可视化配置设计

## 现状

Widget 接入白名单（哪些站点 `website_id` + 允许来源 `Origin` 可嵌入智能问数 Widget）只能通过后端环境变量 `WIDGET_ALLOWED_SITES_JSON` 配置：

- 后端 `api/routes.py::_allowed_widget_sites()` 直接读取 `get_settings().widget_allowed_sites_json`，解析后用于 `_request_context()` 的站点 + Origin 校验。
- 运维要改白名单必须改环境变量并重启服务，非技术人员无法自助管理。

其它运行配置（模型供应商、MySQL/Doris、Skill 运行态）已经有一套成熟的可视化设置链路：
`da_agent_settings` 表（含 `raw_json` 扩展列）→ `skill_admin_service`（合并/归一）→ `nl2sql-admin/settings` 接口 → 前端 `DataAgentConfig.vue`。

## 问题

白名单是面向接入方的高频运维项，却游离在统一设置体系之外，且无 UI。

## 方案

把 widget 白名单纳入现有 admin settings 持久化与接口体系，并在前端新增独立设置页管理。

### 数据与持久化

- 复用 `da_agent_settings.raw_json`，新增 `widget_allowed_sites`（数组）字段，无需新表或新列。
- `skill_admin_store` 的 `_normalize_settings_payload` / `_normalize_settings_row` 增加 `widget_allowed_sites` 的读写（作为 raw_json 扩展键）。
- `skill_admin_service`：
  - 新增 `_normalize_widget_allowed_sites()`：兼容 JSON 字符串或数组，丢弃缺失 `website_id` 的项与重复项，去重 origins。
  - `_runtime_settings_payload()` 中 `widget_allowed_sites` 默认 `[]`（无 env 来源，DB 为唯一数据源）。
  - `_merge_settings_payload()` 透传并归一 `widget_allowed_sites`，支持 patch 覆盖与显式清空（`[]`）。

### 接口

复用现有 `GET/PUT /api/v1/nl2sql-admin/settings`：

- `AdminSettingsResponse` 增加 `widget_allowed_sites: List[WidgetAllowedSite]`。
- `AdminSettingsUpdateRequest` 增加可选 `widget_allowed_sites`。
- `WidgetAllowedSite`：`website_id` / `allowed_origins[]` / `project_name` / `project_color`。

### 运行时取数

`_allowed_widget_sites()` 改为读取 `current_settings_payload()` 的 `widget_allowed_sites`，即设置页保存的“生效白名单”。无任何环境变量来源；未配置或读取失败时返回空列表（请求被拒，安全侧默认关闭）。

环境变量 `WIDGET_ALLOWED_SITES_JSON` 已彻底移除（`config.py`、`deploy/docker-compose.*.yml`、`deploy/.env.example`）。

### 前端

- `IntelligentQueryView.vue` 侧边栏新增独立菜单项「Widget 接入」（tab=`widget`）。
- 新增 `views/settings/WidgetAccessConfig.vue`：站点卡片列表，支持新增/删除站点、编辑 `website_id`/项目名/主题色、动态增删允许来源；脏检查驱动保存；保存前校验 `website_id` 非空且不重复。

## 取舍

- 不新增表/列：白名单是低频写、小体量数据，复用 `raw_json` 最简单且与既有扩展键一致。
- 运行时每次 widget 请求多一次 `current_settings_payload()`（含一次设置记录读取）。widget 请求本就有多次 DB 调用，单次轻量读取可接受；后续如需可加短 TTL 缓存。
- 不做设置页整体布局重构（用户确认本次跳过），仅新增结构清晰的独立页面。

## 影响

- 中型跨层：后端 schema + 持久化 + 运行时取数 + 前端新页面。
- 数据源唯一：白名单只由 DB 持久化与设置页管理，环境变量已移除，减少配置项与心智负担。未配置时默认拒绝所有外部 widget 请求。
