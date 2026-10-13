# 智能体编辑、直接发布及内置技能补迁移验证

实现与主要验收在 2026-10-12 完成，2026-10-13 继续核验持久化结果、补充重新部署回归并清理临时数据。

设计：[工作台](../design/2026-10-05-agent-editor-draft-publish-design.md)、[通用展示技能 §4.1](../design/2026-09-15-dataagent-generic-presentation-skills-design.md)。计划：[工作台](../plans/2026-10-05-agent-editor-draft-publish-plan.md)、[展示技能](../plans/2026-09-15-dataagent-generic-presentation-skills-plan.md)。

## 行为

- 管理员可编辑、保存、发布内置智能体；内置身份限制删除。保存后可直接确认发布，调试为可选步骤，保留管理员权限、版本冲突和重复发布校验。
- 工作台取消 2100px 最大宽度，左右各 16px；发布弹窗显式在视口居中，短屏内部滚动。技能列表移除全局默认设置的启用文案，避免已选技能被误标“未启用”。
- `20261012_000027` 数据迁移补齐默认助手和平台助手的 `chart-visualization`、`report-generation`。保留其它配置和草稿编辑，分别更新草稿 revision 和发布版本，清除旧预览关联，不回写历史话题。
- 普通重新部署仅创建缺失内置智能体，不覆盖已有编辑；数据迁移是单独的升级动作。本次迁移只追加展示技能，之后管理员的选择不会在每次启动时被重新追加。降级保留绑定，撤回需在工作台保存、确认发布。

## 自动验证

| 范围 | 结果 |
|---|---|
| 迁移、智能体配置、草稿发布、可见性 pytest | 51 passed |
| 编辑器、演示接口 Vitest | 22 passed |
| DataAgent SPA build（先 nvm use，Node 20.19.0） | passed |
| git diff --check | passed |

迁移测试执行生产 SQL（SQLite 适配器仅移除不支持的 `FOR UPDATE`），覆盖干净/脏草稿、已有技能、重复运行、缺失草稿、非内置/其它智能体隔离和历史快照；另在真实 MySQL 执行升级。重新部署回归确认现有内置名称、提示词和技能选择不被 seed 覆盖。

## 本地环境与实际流程

- MySQL：已有 Podman `data-portal-mysql`，`127.0.0.1:3306`，会话 schema `dataagent`、业务 schema `opendataworks`，使用 `dataagent` 用户。实际端口与默认开发 Compose 的 3316 不同。
- Redis：复用已有 Podman `odw-local-redis`，`127.0.0.1:6379`，未重新引导。
- Python：`dataagent/dataagent-backend/.venv-py313`，已核验 FastAPI、uvicorn、Alembic、PyMySQL、AnyIO、claude_agent_sdk 导入。
- 服务：DataAgent `8900`、已有 Vite `3001`；实际执行 `pi_agent_core`，本地进程拓扑，复用现有编译产物。真实 DeepSeek 凭据与 `deepseek-v4-pro` 模型，任务带 `execution_mode=auto`，实际预算 1800s。

真实 HTTP/浏览器验证：

1. 升级到 `20261012_000027`。平台助手草稿与发布 profile 均含五个技能，默认助手包含两个展示技能；平台助手工作台确认图表和报告均在列表中。
2. 新建临时智能体，不调试直接发布 v1；重复发布和错误 revision 返回 409。保存新草稿后 `can_publish=true`，错误旧 revision 仍被拒绝。
3. 浏览器打开发布确认，显示真实“待调试”状态；勾选确认后成功发布 v2。发布前话题持久化快照仍为 v1，发布后新话题为 v2。
4. 2560×1440：内容区宽 2352px，三栏区域宽 2320px，左右各 16px。弹窗宽 490px、中心偏差 x/y 均为 0。
5. 390×844：弹窗宽 358px、左右各 16px、中心偏差为 0、页面无水平溢出。1440×480：弹窗高度 448px、上下各 16px、中心偏差为 0、内部可滚动。
6. 发布后的新话题提交真实柱状图请求 A=10、B=25、C=7，任务 accepted，日志确认 waiting → running → finished。运行日志加载两个展示技能，数据库 SDK 记录 `engine_kind=pi_agent_core`；55 条 SDK 记录、两条用户/助手消息持久化，助手状态 finished，正文包含真实 `chart_spec`；实际 SSE 返回 `run.completed`，浏览器显示图表和数据操作按钮。

模型在图表参数上发生过重试，最后成功生成并呈现；本次没有改动技能脚本或运行引擎。

## 清理及范围

临时智能体、草稿、两个话题及关联任务已通过 API 删除，独立 SQL 核验临时 agent 在 profile/draft/topic/task 表均为零。验收专用 Playwright 浏览器已关闭。本地 `8900` 后端已换为正确虚拟环境并加载改动，保留供已有 `3001` 前端使用；已有 MySQL、Redis 保留。

本次覆盖真实发布、话题快照、模型执行、SSE、持久化及图表渲染；未测试生产子容器启动、真实业务 SQL 查询或报告文件生成，未修改这三条路径。报告技能已验证配置绑定和运行加载，不能据此宣称报告生成端到端已通过。

[宽屏工作台](assets/2026-10-13-agent-editor/wide.png) · [居中发布确认](assets/2026-10-13-agent-editor/publish.png) · [真实图表](assets/2026-10-13-agent-editor/chart.png)
