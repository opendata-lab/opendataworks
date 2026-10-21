# DataAgent 运行预算实施计划

设计：[运行预算与默认配置](../design/2026-10-15-dataagent-runtime-budgets-design.md)。

1. `config.py`：整理预算默认、添加 100/200 工具次数和 300/900 Bash 秒数，
   统一解析；移除重复/无效预算，增加正值及超时关系校验。
2. `core/task_submission_service.py`、`core/agent_runtime.py`、`core/task_executor.py`：
   共用模式解析；分发前解析预算，通过内部 `runtime_limits` 跨沙箱传递，保留
   助手轮次覆盖和任务提交时的总超时；打印有效预算。
3. `core/pi_runtime.py`、Pi `frames.ts` / `cell.ts`：明确接收全部预算，传入
   Bash 工具，不再使用隐藏固定 50 次/120 秒限制。
4. 助手 Vue 配置文案说明每次任务轮数和默认继承；部署的外置配置示例使用
   注释列出可覆盖项，文档区分助手、模型和全局；两个 Nginx 模板覆盖默认总预算。
   Compose 和 `.env.example` 默认引擎同步为 Pi；显式配置不自动覆盖。
5. 回归测试：配置解析与校验、助手覆盖、沙箱预算传递、跨进程 init、真实 Bash
   超时；运行 Pi 测试和编译、Python 相关测试、助手 Vue 测试。
6. 使用 `.venv-py313`、本地 Podman MySQL（实测端口）、Redis 6379，启动独立
   smoke 后端端口，真实 HTTP 提交 auto 任务，检查状态、事件、最终消息和取消；
   可用时执行真实 NL2SQL。记录凭据/依赖不足导致的缺失，不宣称未做的验证。
7. 清理 smoke 话题与启动的进程，写验证报告。回滚时整体回退 Python/Pi 协议、
   UI 文案和代理模板；显式旧配置需在升级前按设计迁移，已发布助手不批量重写。

实施与验证结果见 [验证报告](../reports/2026-10-15-dataagent-runtime-budgets-validation.md)。

2026-10-21 提交前补查：修复 Bash 超时/取消的进程组清理，增加后台子进程及
预取消回归；恢复 SDK 测试的环境隔离，组合运行 Python、Pi、Vue 与离线打包
检查通过后提交。Podman 宿主示例按标准 runner 镜像的 Docker CLI 修正文档。
