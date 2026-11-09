# MCP 服务令牌模式计划

日期：2026-11-09。关联：[设计](../design/2026-11-09-mcp-service-token-mode-design.md)。

涉及栈：后端（`backend/app`、`backend/core`）、数据库迁移、文档。前端、MCP 工具契约不变。DataAgent 只改文档（单独 PR，在 opendataagent 仓库）。

## 任务

1. **认证过滤器** `backend/app/.../security/HttpAuthenticationFilter.java`：服务分支只构造 `CurrentPrincipal.service(id)`；读取 `X-Agent-Id`、`X-Agent-User-Id`（去空白、截断到 191/255）放入请求范围的审计上下文。
2. **主体模型** `CurrentPrincipal`、`PrincipalContextHolder`：删除 `delegatedUserKey` 及其在缓存键与相等判断中的使用；新增只读的上报字段访问方式（或独立的 `ReportedCallerContext`），不参与授权。
3. **数据访问** `DatasourceSubjectAccessService`：`loadVisibleColumns` 只用当前主体账号；删除 `checkReadAccess` 与 `ReadAccessIdentity`；`resolveClusterDatasource` 去掉委托分支；无调用方后删除 `resolveUser`。
4. **查询执行与历史** `DataQueryService`：只读查询去掉 `checkReadAccess`；历史写入 `reported_agent_id`、`reported_user_id`；服务账号查询不再写 `executed_by`、`check_account`。
5. **迁移** `backend/app/src/main/resources/db/migration/V59__query_history_reported_caller.sql`；实体 `DataQueryHistory` 增加两个字段。
6. **测试**：
   - 改写 `HttpAuthenticationFilterTest`：带 `X-Agent-User-Id: local:admin` 时主体仍只是服务账号，上报值进入审计上下文；
   - 改写 `DataQueryServiceTest.delegatedReadAuditRecordsResolvedUserAndExplainAccountWithServiceExecutor`：服务账号执行，无 EXPLAIN 检查，历史记录上报字段；
   - `DatasourceSubjectAccountServiceTest`：删除 `resolveUser` 用例（第 81–103 行）与 `resolvingDatasourceRejectsMissingDisabledOrUnmappedDelegates`，新增「上报不存在的用户不影响服务账号查询」；
   - 现有 `PrincipalScopeTest`、`PrincipalContextHolderTest`、`McpContractTest` 与服务账号相关测试全部通过。
7. **文档**：
   - `deploy/README.md` 第 3、5 步：每个问数范围一个服务账号、只读角色、令牌限私网；删除「取双方交集」，改为「上报头只记审计」；
   - `docs/handbook/architecture.md:21`、`docs/handbook/backend-runtime.md:37` 同步；
   - `docs/design/2026-10-26-dataagent-datasource-identity-design.md` 第 2–4 节、9.8、11.3 顶部加「MCP 部分已由 2026-11-09 服务令牌模式取代」；
   - `docs/TODO.md`「门户内嵌问数的用户身份」：浏览器上报用户可冒充一项改为已由令牌模式规避（MCP 不再信任用户头），可信身份待办改为「OAuth 访问令牌模式」并链接本设计；按需移入「已完成」。
8. **DataAgent 文档（opendataagent 仓库单独 PR）**：`README.md`/`README_en.md` 第 44 行、`deploy/README.md:143`、`docs/handbook/architecture.md:22`、`docs/handbook/widget.md:38` 把「执行身份」改为「供审计」，并在部署说明中写明「每个数据范围一个 MCP 记录与服务账号令牌，智能体选择对应 MCP」。

## 验证

- `backend` 单元测试：上述改写与新增用例，以及 `mvn -f backend/pom.xml -Dtest='*Test,!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`（`docs/handbook/testing-guide.md`）全部通过。
- 本地端到端（本机 MySQL 作为被管数据源）：
  1. 建服务账号 A（只读 `demo` 库）和 B（只读 `other` 库），各生成令牌；
  2. 用 A 的令牌调 `/api/mcp/` 列目录、执行 `SELECT`：只见 `demo`，查 `other` 被拒；
  3. 带 `X-Agent-User-Id: local:不存在的用户` 再查一次：结果与不带时相同；
  4. 查询历史中 `agent_id` 为 A，`reported_user_id` 为上报值；
  5. 吊销 A 的令牌后返回 401；
  6. 用门户普通用户登录 Data Studio 查询，仍按该用户自己的角色。
  如能启动 DataAgent，再通过真实智能体问一次（MCP 配置 A 的令牌）。未执行的步骤在 PR 中如实说明。

## 上线

- 发布说明写明行为变化：MCP 不再按 `X-Agent-User-Id` 收窄，范围等于服务账号范围。
- 升级前管理员检查问数服务账号的角色；需要不同范围时先拆分服务账号，在 DataAgent 中为每个范围配置一个 MCP 记录并挂到对应智能体。

## 回滚

- `git revert` 恢复委托收窄；`V59` 新增的两列保留无害。
