# Widget 接入白名单可视化配置实施计划

对应设计：`docs/design/2026-05-29-widget-allowlist-settings-design.md`

## 任务清单

### 后端

- [x] `models/schemas.py`：新增 `WidgetAllowedSite`；`AdminSettingsResponse` / `AdminSettingsUpdateRequest` 增加 `widget_allowed_sites`
- [x] `core/skill_admin_service.py`：新增 `_normalize_widget_allowed_sites()`；`_runtime_settings_payload()` 默认 `[]`（无 env 来源）；`_merge_settings_payload()` 透传归一并写入 flattened
- [x] 移除环境变量 `WIDGET_ALLOWED_SITES_JSON`：`config.py`、`deploy/docker-compose.dev.yml`、`deploy/docker-compose.prod.yml`、`deploy/.env.example`、`website/guide/widget.md`
- [x] `core/skill_admin_store.py`：`_normalize_settings_payload` / `_normalize_settings_row` 增加 `widget_allowed_sites`（raw_json 扩展键）读写
- [x] `api/admin_routes.py`：`_build_admin_settings_response` 输出 `widget_allowed_sites`
- [x] `api/routes.py`：`_allowed_widget_sites()` 改读 `current_settings_payload()`；移除未用的 `json` import

### 前端

- [x] `views/intelligence/IntelligentQueryView.vue`：侧边栏新增「Widget 接入」菜单（tab=`widget`），引入并渲染 `WidgetAccessConfig`
- [x] `views/settings/WidgetAccessConfig.vue`：白名单管理页（站点增删、来源增删、主题色、脏检查保存、保存前校验）

### 测试

- [x] `tests/test_skill_admin_service.py`：归一/合并/清空 widget 白名单用例
- [x] `tests/test_widget_runtime_routes.py`：`install_widget_settings` 补 `current_settings_payload` patch（保证既有 widget 路由用例仍绿）
- [x] `views/intelligence/__tests__/IntelligentQueryView.spec.js`：widget tab 渲染用例
- [x] `views/settings/__tests__/WidgetAccessConfig.spec.js`：渲染/空态/新增保存/校验拦截

## 验证

- 后端：`pytest tests/test_skill_admin_service.py tests/test_widget_runtime_routes.py tests/test_skill_admin_store.py tests/test_topic_task_store.py tests/test_routes_contract.py tests/test_admin_routes.py` 全绿（73 项）
- 前端：`vitest run src/views/settings src/views/intelligence/__tests__/IntelligentQueryView.spec.js` 全绿（32 项）；`vite build` 通过
- 本地全链路冒烟（带真实 MySQL 的 `PUT /settings` → `da_agent_settings.raw_json` 出现 widget_allowed_sites → widget 请求按新白名单放行/拒绝）未在本环境执行（无本地 MySQL）

## 回滚

- 还原上述文件；DB 中 `raw_json.widget_allowed_sites` 字段被忽略不影响旧逻辑
- 运行时若回退 `_allowed_widget_sites()` 读 env，则恢复纯环境变量白名单
