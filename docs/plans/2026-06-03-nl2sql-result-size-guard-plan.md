# 智能问数结果体积守卫 — 执行计划

- 日期：2026-06-03
- 关联设计：`docs/design/2026-06-03-nl2sql-result-size-guard-design.md`
- 受影响栈：`backend`（agentapi 查询/导出）、`dataagent`（portal-mcp 透传无需改动、skill 脚本与运行时桥接）

## 任务与触达文件

1. 响应契约新增字段 — `backend-agent-api/src/main/java/com/onedata/portal/agentapi/dto/AgentReadQueryResponse.java`
   - 增 `truncated_by_size`（Boolean）、`notice`（String），默认 `null`。

2. 后端字节预算守卫 — `backend/src/main/java/com/onedata/portal/agentapi/service/BackendAgentQueryService.java`
   - 注入 `ObjectMapper`（Spring 容器已有）。
   - 新增 `@Value("${agent.query.max-result-bytes:524288}") int maxResultBytes`。
   - 新增私有方法 `applyResultByteBudget(rows)`：逐行估算紧凑 JSON 字节并累加，超预算处截断；返回截断后的行 + 是否截断标记。
   - `readQuery` 在 `setRows` 前应用守卫；截断时设置 `rowCount`/`hasMore=true`/`truncatedBySize`/`notice`。

3. export 行数安全上限 — `backend/src/main/java/com/onedata/portal/agentapi/service/BackendAgentMetadataService.java`
   - 新增 `EXPORT_MAX_ROWS=5000`；`exportTables/exportLineage/exportDatasource` 返回前截断并 `log.warn`。

4. 运行时桥接透传 — `dataagent/.claude/skills/opendataworks-platform-tools/scripts/_opendataworks_runtime.py`
   - `query_readonly()` 返回字典补 `truncated_by_size`、`notice`。

5. skill 脚本提示 — `dataagent/.claude/skills/opendataworks-platform-tools/scripts/run_sql.py`
   - 成功分支读取 `truncated_by_size`/`notice`；截断时在 `print_json` 输出带上两字段，并把 `execution_detail.stop_reason` 改为截断引导语。

6. skill 文档 — `dataagent/.claude/skills/opendataworks-platform-tools/SKILL.md` 及相关 `reference/*`
   - 在 `sql_execution` 结果 schema 说明中补 `truncated_by_size`/`notice` 与“截断后应缩小范围”的恢复规则。

## 验证

- 后端：`mvn -pl backend -am -Dtest=BackendAgentQueryServiceTest test`
  - 新增用例：构造超预算大结果，断言 `truncatedBySize=true`、`hasMore=true`、`rows` 被截断、`notice` 非空；小结果不截断、字段为 `null`。
- portal-mcp：`pytest dataagent/portal-mcp/tests/test_app.py`（契约透传，应保持绿色；如断言响应字段需补充）。
- skill 脚本：`pytest dataagent/dataagent-backend/tests/test_metadata_cli_bridge.py`（query_readonly 桥接透传）。
- 端到端冒烟（环境可用时，按 AGENTS.md 本地冒烟法）：对返回大结果的真实 NL2SQL 请求验证不再触发缓冲溢出、工具结果出现 `notice` 且 run 正常收尾；若无法本地起全链路，明确标注脚本路径/后端单测已验证、端到端未验证。

## 回滚

- 改动均为新增字段 + 截断守卫，向后兼容。回滚即还原上述文件；`agent.query.max-result-bytes` 调大可临时放宽守卫。
