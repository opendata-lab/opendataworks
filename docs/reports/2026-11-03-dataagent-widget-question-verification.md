# DataAgent widget 问题卡片接入验证

日期：2026-11-03（Asia/Shanghai）。本次只验证接入与界面修复，未修改 OpenDataWorks 业务代码、权限或模型配置。

## 结论

OpenDataWorks 通过远程 widget bundle 引用独立 opendataagent。新版支持 AskUserQuestion 的问题卡片，内嵌和悬浮入口均已通过真实模型验证；答案提交、继续执行、事件与最终消息持久化、历史恢复均正常。

只读 MCP 元数据查询成功，返回唯一数据源 `xinghe_retail`。工作流列表查询受当前服务账号权限边界限制，被拒绝，不能把这次结果记为工作流问数成功。

## 当前引用与配置

- `frontend/src/utils/dataagentWidgetLoader.js`：默认加载 `/dataagent/widget/dataagent-widget.bundle.js`；`VITE_DATAAGENT_WIDGET_JS_URL` 可覆盖脚本地址。无需 npm 引入或复制 DataAgent 源码。
- `frontend/src/views/IntelligentQueryRemoteEmbed.vue`：智能问数页安装 inline widget；`frontend/src/views/Layout.vue`：其他页面安装 floating widget。两者 `apiBaseUrl` 均为空，通过门户同源代理调用 DataAgent API。
- 门户 `GET /api/v1/settings/agent` 当前配置：`queryAgentId=agent_opendataworks`，名称为 OpenDataWorks平台助手。
- widget 站点：`opendataworks-portal`，允许来源 `http://127.0.0.1:3000`、`http://localhost:3000`，不允许匿名。当前登录上报 `local:admin`。
- 助手绑定 MCP `portal`：普通 configured HTTP 记录，地址 `http://127.0.0.1:8801/mcp/`。凭据保留在安全配置，本报告不记录 Token。
- 助手技能：`opendataworks-business-knowledge`、`opendataworks-platform-tools`、`opendataworks-data-dev`、`chart-visualization`、`report-generation`。
- 当前本地设置已齐全，本次无需新增配置；重新构建 widget 后，重启 DataAgent 前端开发服务以清除旧 bundle 的转换缓存，确认门户加载的新脚本包含 QuestionCard，再刷新浏览器。

## 生产接入要求

按 [部署说明](../../deploy/README.md#接入-dataagent) 配置：

1. 单独部署新版 DataAgent，前端默认端口 8901、后端 8900。
2. 在 `deploy/docker/nginx/frontend.conf` 填写实际 DataAgent 主机并启用现有转发段：`/dataagent/widget/` 到前端 `/widget/`；`/api/v1/dataagent/`、`/api/v1/nl2sql-admin/`、`/api/v1/nl2sql/` 到后端。仓库模板默认仍注释，部署时必须启用。流式段保持关闭缓冲和现有超时设置。
3. 在 DataAgent Widget 接入登记 `opendataworks-portal`，允许来源填写真实门户 origin（协议、主机、端口）。
4. 创建助手，导入并启用所需 odw-skills，绑定 HTTP MCP 与有效服务账号 Token；在门户智能助手设置选择该助手。
5. 查询业务数据库还需要服务账号和当前用户的数据源权限、受限数据库账号映射。现有身份信任限制见 [待办](../TODO.md)。

已有接入环境获得本次问题卡片修复，只需更新 DataAgent 前端中的 widget 构建产物并刷新门户，无需为卡片增加独立配置，也无需重建 OpenDataWorks 前端。

## 验证环境

复用本机运行中的服务：

- OpenDataWorks 前端 `127.0.0.1:3000`，Java 后端 `127.0.0.1:8082`。
- DataAgent 前端 `127.0.0.1:3001`、后端 `127.0.0.1:8900`；Python 3.13，加载独立仓库 `dataagent-backend/.venv-py313` 的依赖。
- MCP `127.0.0.1:8801`。
- MySQL `127.0.0.1:3306`，会话 schema `dataagent`，复用 Podman 的 `data-portal-mysql`；不是新 compose 的 3326 映射。
- Redis `127.0.0.1:6379`，复用 Podman 的 `odw-local-redis`，未重新引导依赖。
- 引擎 `pi_agent_core`（已核对 `da_agent_sdk_record.engine_kind`），真实 DeepSeek `deepseek-v4-pro` 执行，未使用 mock。

## 场景与证据

1. 首次真实请求因模型服务余额不足返回 402；用户充值后重试通过，余额问题已解决。
2. 内嵌 widget：要求 AskUserQuestion 提供按天／按周选项，卡片正常展示；点击按天后折叠为“已补充 1 项 · 按天”，模型回复 `smoke-ok：按天`。任务 `task_3c42789a95e14319813cff78` 最终 `finished`。
3. API 确认 SDK 事件持久化包含 `question_request` 与 `question_answer`，答案为按天；最终 assistant 消息已保存。重新打开带 topicId 的门户问数页，问题卡片和答案恢复正常。
4. 悬浮 widget：要求选择工作流数量／数据源数量，卡片正常显示并接收工作流选择；模型继续调用 `odw_list_workflows`，但工具返回服务账号不能调用开发写接口。任务 `task_f94b2c599fb64cc8892fc5eb` 正常生成说明并完成；业务查询受限，不算查询成功。
5. 同会话改查数据源：`odw_export_metadata` 与 `odw_resolve_datasource` 均执行成功，返回去重后 1 个 `xinghe_retail` 数据源，模型答复在门户正常展示。任务 `task_e518faa3aea840c9aaecd698` 最终 `finished`，assistant 内容已保存。
6. 前序修复验证：opendataagent 中 widget 31 项组件测试通过，`build:widget` 成功。

未覆盖 SQL 实际执行、生产 child-container 跳转、生产代理部署及取消分支。本次是问题卡片界面和真实 MCP 元数据链路验证，不扩大为全部智能问数能力验收。

## 清理与限制

3 个本次新建的测试话题已通过 API 删除，包括首次余额不足的话题；未修改已有业务会话。复用的后端、数据库、Redis 与 MCP 保持运行，前端开发服务保持可供本地继续试用。

工作流只读列表受服务账号统一门禁限制的后续已记入 [TODO](../TODO.md)。截图保存在 opendataagent 的 `output/playwright/odw-widget-question.png`、`odw-widget-question-answered.png`、`odw-widget-floating-question.png` 与 `odw-widget-mcp-result.png`。
