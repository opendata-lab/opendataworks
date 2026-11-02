# 现有双身份链路本机验证

日期：2026-11-01。来源：用户要求签名身份方案先记TODO，优先跑通已实现功能。关联[设计9.15](../design/2026-10-26-dataagent-datasource-identity-design.md#identity-local-smoke)、[计划第5节](../plans/2026-10-26-dataagent-datasource-identity-plan.md#identity-local-smoke-plan)、[身份待办](../TODO.md#门户内嵌问数的用户身份)。

## 当前实现与范围

- 现有实现要求用户和智能体两个身份，缺一拒绝；不存在仅智能体执行模式。DataAgent逐请求注入`X-Agent-User-Id`、`X-Agent-Id`，静态MCP配置中的同名身份头会被运行时覆盖。
- 本机助手为`agent_opendataworks`。源1为MySQL；管理员`local:admin`绑定个人`root`账号，智能体绑定`odw_query_ro_local`，后者只读样例库。实际MCP连接配置使用`X-Portal-MCP-Token`，尚无每智能体签发的Portal Bearer Token。调用Java另用内部服务凭证；两者均未输出或修改。
- 真实对话通过widget现有协议显式使用`local:admin`，属于本机定向验证输入；没有新增生产写死用户、缺身份回退或智能体独立模式。用户身份仍存在浏览器可伪造的已知缺口，本轮不作为可信多用户隔离验收。
- 本机启用的未删除平台用户原来只有admin；数据库未发现`datasource_role%`表。角色／成员权限中心与用户管理仍是原型，不能称为已实施。为了验证普通用户路径，用临时平台用户和已有只读账号映射作为测试夹具，不代表已交付创建用户界面。

## 环境与方法

门户前端`127.0.0.1:3000`、Java后端8082，平台库`odw_split_local_20261029`；MySQL3306、Redis6379，独立DataAgent8900、widget3001、ODW MCP8801。复用正在运行的服务，不重启或更改模型配置。模型实际返回`deepseek-v4-pro`。

使用独立仓库`dataagent-backend/.venv-py313/bin/python`，已先验证`dotenv`、`pymysql`、`httpx`、`mcp`与`bcrypt`可导入。原预期`odw-mcp-server/.venv/bin/python`不存在，首次命令未执行；改用上述已验证依赖的Python3.13虚拟环境，未据此判定MCP服务故障。没有前端或后端应用代码变化，未重跑构建／单元测试。

实际复跑的已有只读MCP检查命令（DATAAGENT_BACKEND_DIR指向独立仓库后端目录）：

```sh
"$DATAAGENT_BACKEND_DIR/.venv-py313/bin/python" /tmp/odw-check-mcp-query.py
```

其他检查通过同一解释器运行临时Python HTTP／SQL验证，操作接口如下；数据库准备、清理与功能请求分开执行。临时口令随机生成且不输出，未扩大既有数据库账号权限。

## 结果

| 场景 | 请求／检查 | 结果 |
| --- | --- | --- |
| 服务健康 | MCP `/health`、DataAgent `/api/v1/nl2sql/health` | 均HTTP200；门户无登录访问`/api/auth/me`返回401 |
| 管理员登录与身份 | `/auth/login`、`/auth/me` | 成功，确认`local:admin`；未改变浏览器登录状态 |
| 管理员目录与SQL | 源1`/databases/xinghe_retail/tables`、`/v1/data-query/execute` | 6张表可见；已完成订单12单、实付668.90 |
| MCP授权查询 | `odw_query_readonly`，城市分组JOIN | 临江7单／351.80，青岚5单／317.10；合计一致 |
| MCP越权查询 | 读取`dataagent.aq_chat_message`的计数 | `isError=true`，智能体账号拒绝，未读取聊天明细 |
| 普通用户 | 临时用户登录、身份、目录、SQL、越权SQL、管理API、停用及清理检查 | 8项断言通过、0失败；含下方说明 |
| 真实问答 | 创建topic、提交task、读取SDK事件与消息 | 最终`finished`且无error，调用表结构／检索／只读SQL工具，结果12／668.90 |
| 对话持久化与清理 | topic消息列表、DELETE topic、再次GET | 用户与助手两条消息落库；清理后404 |

普通用户8项断言分别为：登录成功、`role=user`且userId正确、看到6张样例表、SQL返回12／668.90、未授权库SQL被拒、不能查看账号映射管理接口、停用后不能继续浏览数据、临时映射删除成功。登录和只读数据查询走真实HTTP与MySQL；平台用户夹具通过数据库准备，映射通过现有管理员API创建。临时用户结束时停用并软删除，保留查询审计；未实现／测试用户管理UI。

真实问答任务`task_163d1b5ddf2d4922b6ae7643`，话题`topic_ad629bb8f7514c20be5df0a3`。使用`execution_mode=auto`，经现有门户widget协议提交：统计`xinghe_retail.orders`中`status='已完成'`的订单数和`SUM(pay_amount)`。持久化SDK事件确认调用`odw_get_table_ddl`、`odw_search_tables`以及两次`odw_query_readonly`；最终助手消息包含12和668.90，与门户直接SQL一致。测试话题已清理，以上ID仅用于本报告追溯。

## 验证边界

本轮为真实HTTP／MCP／数据库／模型链路验证，未操作浏览器，也未重新验收页面渲染。未实施签名用户上下文、OAuth委托、每智能体Portal Token、新角色权限中心或用户管理；未补真实Doris、第三方OAuth和生产子容器拓扑验证。相关缺口保持TODO开放，不能将本机固定测试身份验证推广为生产多用户安全验收。
