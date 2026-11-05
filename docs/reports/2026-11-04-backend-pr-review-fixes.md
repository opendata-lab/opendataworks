# PR #493 评审修复与范围精简

日期：2026-11-04。按用户后续反馈收缩实现，以本报告及[当前设计](../design/2026-11-03-backend-mcp-auth-consolidation-design.md#pr评审后契约修正2026-11-04)为准；[计划](../plans/2026-11-03-backend-mcp-auth-consolidation-plan.md#用户要求的范围精简2026-11-04)同步更新。

## 保留的功能修复

- 任务input／output半边血缘正常返回；非空端点对应隐藏或缺失表仍过滤。
- 元数据导出超过5000行明确失败，不静默截断。512KiB响应预算与行数是不同限制，不能保证所有5000行结果一定超过字节预算。
- 无必填参数的MCP工具允许省略arguments；建表保留显式owner，更新任务不覆盖原负责人。
- 成员授权／调整／绑定／移除失败刷新成员与操作记录，保留错误，隔离切源后的迟到响应；APPLYING显示执行中。
- 管理连接与成员连接权限错误分别提示；委托查询审计记录解析后的USER id、EXPLAIN账号和SERVICE执行账号；数据源解析继续校验可选委托映射。
- 创建数据库账号使用20秒Statement超时，保留密码脱敏与UNKNOWN语义；平台列标签兼容大写snake_case及camelCase；清理Skill／Schema／部署残留。
- 门户Nginx继续拒绝/api/mcp及子路径，外部MCP使用独立后端入口。

## 精简内容

1. 删除TLS专用代理验证脚本、对应测试和新增部署说明，也删除独立的角色并集故障注入脚本。当前HTTP部署不需要这些验证设施。
2. 删除过滤器里的URL解码、拆分及路径拒绝逻辑。认证直接使用容器解析后的servletPath；AuthenticationRoutePolicy集中识别/mcp、/mcp/和/mcp;路径。普通用户Cookie仍不能进入MCP；真实HTTP验证包含矩阵参数及编码路径。
3. 删除已有成员旧／目标角色并集预写和失败回滚事务。已有成员仅标记APPLYING，成功后保存目标集合，失败统一ERROR。首次自动建号保留先保存加密凭据和目标角色的事务，保证失败重试复用原账号。删除复杂保护用例，改为复用现有错误测试验证ACTIVE／APPLYING／ERROR三种旧状态失败都落到ERROR，且不改写旧角色关联。

这不承诺自动修复数据库与平台的部分成功差异：原生GRANT成功、后续失败时，新角色可能尚未计入平台memberCount；ERROR状态本身不会阻止该角色删除。当前由管理员先核对授权记录并恢复成员，再删除角色。按用户选择不增加其他保护机制，限制登记于[统一待办](../TODO.md)。

## 精简后验证

- Java四模块重新编译、测试与package：629项通过（core580、api18、mcp-server8、app23），零失败／错误／跳过。
- Python部署／离线包／Skill契约：14项通过。
- 本地HTTP：管理员登录、普通用户Cookie拒绝MCP、合法SERVICE Token正常访问MCP，包含矩阵参数／编码路径，11项通过。编码分号路径未匹配工具路由时SERVICE返回404，普通用户仍先被认证层以401拒绝，不增加该路径别名。
- 现有managed_accounts_smoke.py：真实MySQL自动建号20项通过，覆盖首次失败与重试、多角色复用、撤销、同名保护、并发首授与移除。
- 本轮未改前端代码，不重复运行前端测试；上轮487项及Vite构建已通过。本轮不重跑TLS、PG、Docker镜像构建或独立DataAgent模型流程。

日志为/tmp/odw-slim-java.log、/tmp/odw-slim-python.log、/tmp/odw-slim-http.log、/tmp/odw-slim-managed-mysql.log。私有凭据及测试状态不提交。后端8082更新至精简后的jar，前端3000保持运行，演示成员保留。

3947775a的633项Java、65项真实链路及正式Docker构建结果属于此前实现的历史验证；本次删除部分测试和验证脚本，以上述当前结果为准。Doris实库与独立DataAgent模型验收仍见统一待办。
