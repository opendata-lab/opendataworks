# 废弃智能体身份请求头清理

日期：2026-11-03收尾，工作于11-02开始。依据[设计14](../design/2026-10-26-dataagent-datasource-identity-design.md#14-废弃身份请求头残留清理2026-11-02)、[计划10](../plans/2026-10-26-dataagent-datasource-identity-plan.md#10-清理旧身份头2026-11-02)，提交现有PR #491。

## 改动

- 删除未使用的AgentIdentityContext.AGENT_HEADER常量。
- 删除MCP operator ContextVar、getter／setter和中间件清空／恢复代码，移除只验证该已删状态读写的旧测试及错误的“会转发操作人”注释。
- 四个Java Controller不再声明X-Agent-Operator请求参数，涵盖11个写操作；三个调度Service的operator参数在实现中未使用，连同接口／传参删除。其余实际用于owner／审计的8个参数保留，由Controller从已认证服务账号上下文获取ID，不接受客户端操作人，也不伪造自然人。
- 清理普通读测试中的旧头，保留伪造X-Agent-Id／X-Agent-Operator的负向测试，强化转发白名单和跨请求身份清理断言。
- 保留正在使用的内部agentId字段与业务审计参数。Bearer服务Token、内部hop认证、可选用户交集和开发写接口403白名单不变；没有启用新的写能力。

生产目录backend-agent-api/src/main、backend/src/main/java及odw-mcp-server/odw_mcp_server中，X-Agent-Id、X-Agent-Operator、AGENT_HEADER、operator_header搜索均为零。旧头仍出现在负向测试和历史说明中，不能为清除文本命中而删除安全回归。

## 自动验证

| 验证 | 结果 |
| --- | --- |
| Java8定向与打包 | 23项，失败0／错误0／跳过0；agent-api模块10项、主backend13项；Maven BUILD SUCCESS。 |
| MCP Python全模块 | 43项通过；2项既有Starlette／Pydantic依赖警告。原44项中删除1项只验证无用operator ContextVar的旧用例。 |
| Python环境／语法 | 专用Python3.13虚拟环境已安装MCP模块requirements，httpx／pytest等依赖可导入；真实冒烟脚本py_compile通过。 |
| 静态核对 | 上述生产残留搜索与git diff --check通过；文档相对链接存在。 |

新增AgentControllerAuditIdentityTest三项：8个有审计入参的方法均取已认证服务ID，三个调度方法不再传无用operator；MVC收到伪造旧头不能改变审计身份；缺认证上下文拒绝调用业务服务。MVC审计来源测试有意隔离拦截器，生产写403另由拦截器单测和真实HTTP验证，不能将其成功响应理解为开放了写API。

命令：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_361.jdk/Contents/Home mvn -pl backend -am -Dmaven.compiler.useIncrementalCompilation=false -Dtest=AgentApiAuthInterceptorTest,AgentApiConfigurationTest,AgentControllerAuditIdentityTest,AgentIdentityContractTest,AgentQueryControllerTest,AgentMetadataControllerTest,BackendAgentWorkflowServiceTest -Dsurefire.failIfNoSpecifiedTests=false package
<python-venv>/bin/python -m pytest -q odw-mcp-server/tests
<python-venv>/bin/python -m py_compile backend/src/test/scripts/legacy_identity_headers_smoke.py
```

## 真实Java／MCP／MySQL链路

测试外部操作更新本机Java8082及MCP8801，保留平台库、认证凭据和既有服务账号Token；不修改独立DataAgent代码或运行完整模型对话。复用演示源xinghe_retail，以COUNT只读查询对照Java与MCP结果。冒烟脚本22项通过：

1. Java有效Token解析为既有服务账号ID；伪造两个旧头不会替换主体。
2. 只有内部hop凭据或只有Bearer时均401，旧头不能补足认证。
3. Java服务独立查询成功，注入旧头结果不变。
4. 任务、建表、工作流、发布、调度及元数据补全共11个写入口全部403；使用不存在的目标ID及空草稿，避免防护异常时修改真实对象。
5. MCP独立查询、注入旧头的查询以及显式给定现有普通用户身份的交集查询，均与Java结果一致。
6. MCP仅旧头、假服务Token两种请求均401。

本次没有创建账号、Token、原生角色或临时表，没有修改授权和业务数据；认证会正常更新服务Token最后使用时间。可选用户头仍只是当前未签名标识，可信自然人委托待办未关闭。

```sh
# integration JSON至少含token、service_id；backend-env JSON含AGENT_API_SERVICE_TOKEN。
# private配置与Token禁止输出或提交。
<python-venv>/bin/python backend/src/test/scripts/legacy_identity_headers_smoke.py --integration <private-integration.json> --backend-env <private-backend-env.json> --database xinghe_retail --human-id local:admin
```

本轮没有前端变更，不重复前端测试／构建；没有重新做真实模型、完整工作流或PG／Doris验收。不把此次22项身份链路验证替代此前其他功能的验收范围。

## 交付与回退

更新账号手册及同主题设计／计划、统一TODO完成记录，提交推送现有PR #491。预先存在的Docker／CI改动及本机独立仓库不包含。回退仅还原本轮代码并重启旧Java／MCP，无数据库迁移或授权恢复。
