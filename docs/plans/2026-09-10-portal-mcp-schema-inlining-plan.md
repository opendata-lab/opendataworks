# portal-mcp 工具 Schema 内联 — 执行计划

- 日期：2026-09-10
- 关联设计：`docs/design/2026-09-10-portal-mcp-schema-inlining-design.md`
- 受影响栈：`dataagent/portal-mcp`（独立镜像）；经 MCP 协议影响所有 DataAgent 运行时

## 1. 任务分解与触达文件

### 阶段 1：内联器与发布后处理

- `dataagent/portal-mcp/portal_mcp/app.py`
  - 新增 `SchemaInlineError`、`_resolve_json_pointer()`、`_inline_refs()`、`_inline_tool_schema_refs()`
  - `build_mcp_server()` 返回前调用 `_inline_tool_schema_refs(mcp)`
  - 内联器只接受「无 siblings 的本地 `$ref`、目标为对象」这一种形状，其余抛错
  - 删除 `$defs` 前校验输出无残留 `$ref`

### 阶段 2：失败处理分级

- `_inline_tool_schema_refs(mcp, *, strict=False)`
  - 默认：单工具失败记 `logger.error` 并保留原 schema，服务照常启动
  - `strict=True`：抛错，供测试与 CI 阻断回归

### 阶段 3：测试

- `dataagent/portal-mcp/tests/test_app.py`
  - 递归断言所有工具 `inputSchema` 无 `$ref` / `$defs`
  - 断言内联后字段集合、`additionalProperties: false`、字段描述保留
  - 断言嵌套模型展开后**有实际内容**（camelCase alias、嵌套 `additionalProperties`），避免「ref 变成 `{}` 也能通过」
  - 分别覆盖 siblings、递归、悬空、布尔目标四种拒绝形状
  - 断言真实工具调用仍拒绝未知字段与违反跨字段校验的入参
  - 断言坏模型不阻止服务构建，且不影响其他工具；`strict=True` 下抛错

## 2. 验证方案

### 自动化测试

```sh
cd dataagent/portal-mcp
../dataagent-backend/.venv-py313/bin/python -m pytest tests/ -q
```

- 预期 40/40 通过
- 必须在 `requirements.txt` 锁定的 `mcp[cli]==1.28.1` 上运行（venv 曾装成 1.27.0）

### 有效性验证（测试必须能抓到回归）

注释掉 `build_mcp_server()` 里的 `_inline_tool_schema_refs(mcp)` 调用后重跑，相关断言必须失败；恢复后必须全绿。

### 端到端验证

- 复刻 `pi-ai` non-strict 重建（只保留 `type` / `properties` / `required`），断言模型仍能看到 `database` / `table` / `table_id` 与 `additionalProperties: false`
- 统计 23 个 schema 内联前后总字节数，确认无膨胀

### 部署后验证（待办）

portal-mcp 为独立镜像，改动需重建并重启后才生效：

1. 重建 `mikefan2019/opendataworks-portal-mcp:1.5.0`
2. 重启内网 portal-mcp 服务
3. 重跑内网问数「生产环境有多少个分级保障组件？」
4. 确认 `portal_get_table_ddl` / `portal_query_readonly` **一次调用成功**，不再退化到脚本 fallback

## 3. 回滚与风险预案

- **回滚**：删除 `build_mcp_server()` 中的 `_inline_tool_schema_refs(mcp)` 单行调用即可恢复原发布行为；工具注册与 Pydantic 模型未被改动
- **私有成员风险**：依赖 `mcp._tool_manager`。升级 `mcp[cli]` 时若结构变化，测试的「无 `$ref`」断言会立即失败；实测 `mcp 2.2.0` 该结构未变
- **模型形状风险**：未来若新增递归或带 siblings 的模型，生产降级为原 schema（不劣于改动前），CI 在 `strict=True` 下阻断
