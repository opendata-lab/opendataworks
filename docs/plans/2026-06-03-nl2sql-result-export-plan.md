# 智能问数 SQL 结果导出文件能力 — 执行计划

- 日期：2026-06-03
- 关联设计：`docs/design/2026-06-03-nl2sql-result-export-design.md`
- 受影响栈：`backend`（agentapi 查询）、`dataagent`（odw-cli、运行时桥接、skill 脚本与文档）

## 任务与触达文件

1. 请求字段 — `backend-agent-api/src/main/java/com/onedata/portal/agentapi/dto/AgentReadQueryRequest.java`
   - 增 `for_export`（Boolean）。

2. 后端导出模式 — `backend/src/main/java/com/onedata/portal/agentapi/service/BackendAgentQueryService.java`
   - `readQuery`：`for_export=true` 时跳过字节守卫，直接返回（仍受 `MAX_LIMIT` 行上限）。

3. odw-cli — `dataagent/.claude/skills/opendataworks-platform-tools/bin/odw-cli`
   - `parse_query_readonly` 增 `--for-export` → 请求体加 `"forExport":true`；更新 usage。

4. 运行时桥接 — `dataagent/.claude/skills/opendataworks-platform-tools/scripts/_opendataworks_runtime.py`
   - `query_readonly(..., for_export=False)`：为真传 `for_export="true"`。

5. 导出脚本（新增）— `dataagent/.claude/skills/opendataworks-platform-tools/scripts/export_query.py`
   - 查询全量 → 写工作区 CSV → 输出 `sql_export` 契约（路径+列+行数+预览）。

6. skill 文档 —
   - `dataagent/.claude/skills/opendataworks-platform-tools/SKILL.md`（工具清单 + 路由规则）
   - `reference/50-tool-output-contract.md`（`sql_export` 契约）
   - `reference/30-tool-recipes.md`（大结果导出配方）

## 验证

- 后端：`mvn -pl backend -am -Dtest=BackendAgentQueryServiceTest test`
  - 新增用例：`for_export=true` 且结果超字节预算时，不截断、`truncated_by_size` 为 null。
- odw-cli：`pytest dataagent/dataagent-backend/tests/test_odw_cli.py`
  - 新增用例：`--for-export` 时请求体含 `"forExport":true`。
- 脚本：`python -m py_compile export_query.py`；用 monkeypatch `query_readonly` 的轻量测试验证写 CSV + 预览输出。
- 端到端冒烟（环境可用时）：对真实大结果查询用导出脚本，验证 CSV 落盘、行数完整、stdout 只回预览且不触发缓冲溢出；若无法本地起全链路，明确标注未跑端到端。

## 回滚

- 改动均为新增字段/开关/脚本，向后兼容；回滚即还原上述文件。
