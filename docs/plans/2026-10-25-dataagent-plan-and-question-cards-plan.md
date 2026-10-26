# 问题卡片、计划卡片与计划开关：实施计划

设计：[2026-10-25-dataagent-plan-and-question-cards-design.md](../design/2026-10-25-dataagent-plan-and-question-cards-design.md)

涉及栈：DataAgent 后端（Python）、Pi 运行时（Node）、对话组件（`dataagent-frontend/packages/agent-conversation`）、Agent 管理页。主后端和主前端不改。

界面以 OntoFoundry 用户提供的参考截图为准：问题卡片、计划卡片、输入框的“计划”开关。截图不进入仓库。

## 1. 现状

- Claude Code 引擎：
  - 已支持 `AskUserQuestion`，会写入 `question_request` 块，任务停在 `waiting_input`；
  - 已支持计划模式：模型调用 `ExitPlanMode` 提交计划，写入 `risk_level=plan` 的 `permission_request` 块，任务停在 `waiting_permission`；
  - 用户回复都写在 MySQL，执行器轮询读取。
- Pi 引擎：
  - 没有这两个工具；
  - stdio 协议只有 `cell.init`、`run.cancel`、`cell.shutdown` 三种入站帧；
  - 不接收权限模式。
- 等待没有期限。用户点“继续完善”拒绝计划时，模型只收到固定文案，看不到用户的修改意见。
- 对话组件已有 `QuestionCard`、`PermissionCard` 和权限模式下拉。

## 2. 任务

### T1 后端：等待超时与计划意见

- `permission_wait.wait_for_decision` 和 `ask_user_question.wait_for_answer` 接受截止时间 `deadline` 和超时回调 `on_timeout`；回调写库失败时下一次轮询重试。修改意见由 `user_interaction.request_plan_approval` 读取，返回 `(decision, note)`。
- 问题卡片超时后：
  - 有推荐项的题选推荐项，推荐项按 `recommended: true` 识别，或按标签末尾的“（推荐）”“(推荐)”“(Recommended)”识别；
  - 没有推荐项的题记为跳过；
  - 写入 `question_answer`，带 `source: "auto_timeout"`。
- 计划卡片超时按 Agent 的 `plan_timeout_action` 处理：`approve` 写入 `allow` 并带 `source: "auto_timeout"`；`park` 继续等待。
- 用户选“先调整计划”时，提交 `deny` 并在 `note` 里带修改意见，执行器返回给模型的拒绝消息要包含这段意见。
- 写入记录只走现有的 `append_*_if_waiting`，保证用户回答和超时自动作答之间只有一个生效。
- 配置项：`dataagent_interaction_timeout_seconds`（全局，默认 600，0 表示一直等待），问题卡片和 `approve` 的计划卡片共用。

### T2 Agent 配置：计划超时行为

- 迁移：在 `da_agent_profile` 表新增 `plan_timeout_action VARCHAR(16) NOT NULL DEFAULT 'park'`。草稿和发布流程都带上这个字段。
- `agent_profile_service` 负责读写和校验（取值只能是 `park` 或 `approve`），快照中带上该字段。
- Agent 详情页增加“计划超时”选择项：“继续等待”或“自动执行”。

### T3 Pi 引擎

- 协议：
  - 出站新增 `interaction.request` 帧：`{request_id, kind: "question"|"plan", payload}`；
  - 入站新增 `interaction.resolved` 帧：`{request_id, result}`；
  - `cell.init` 增加 `permission_mode` 和 `plan_denied_tools`。
- 工具：
  - 新增 `AskUserQuestion`，入参与 Claude Code 的一致（`questions[]`：`question`、`header`、`options[{label, description, recommended?}]`、`multiSelect`）；
  - 新增 `ExitPlanMode`（入参 `{plan}`），只在 `plan` 模式下提供；
  - 两个工具执行时都发出 `interaction.request` 并等待结果，结果作为工具输出交给模型。
- 计划模式：批准前，`plan_denied_tools` 中的工具由 `beforeToolCall` 拦截，批准后解除。名单是 `permission_gate` 中的 `WRITE_TOOL_NAMES` 和 `PLAN_DENIED_BUILTIN_TOOLS`，与 Claude Code 引擎一致；Bash 不在名单里，见设计 PQ-04。拒绝时把用户意见作为工具结果交给模型。
- Python `pi_runtime`：
  - 收到 `interaction.request` 后，把执行器传入的处理函数（写块、改任务状态、等待回答）放到独立的协程里运行，完成后回写 `interaction.resolved`；
  - 等待期间，主循环继续读帧、检测子进程退出和轮询取消，只暂停总超时和空闲超时；
  - 取消时直接发 `run.cancel`，不把“已取消”当作工具结果回给 Cell。
- 计划模式下，系统提示末尾追加一段通用说明：先调研，调用 `ExitPlanMode` 提交计划，批准前不产出结果文件。这段说明放在 `prompts/` 中，不写进技能。

### T4 对话组件（按截图）

- 问题卡片：
  - 每次显示一题，右上角显示“1 / 3”；
  - 选项带编号、标题和说明，推荐项标“（推荐）”；
  - 选项下方是带铅笔图标的输入行“补充你的想法，或给出不同的答案…”，左下角有“跳过”，右上角“×”跳过全部；
  - 点选项即进入下一题，答完最后一题自动提交；
  - 有截止时间时显示倒计时；已作答后显示结果，自动作答的标“已按推荐自动选择”。
- 计划卡片（`risk_level=plan`）：
  - 头部：灯泡图标和“计划”，右侧下载、复制、展开三个按钮；
  - 正文：Markdown 预览，限高并渐隐；
  - 下方“实施此计划？”框：“是，开始执行 →”、“✎ 先调整计划”（点后展开输入框，提交时作为 `deny` 并带 `note`）。
- 展开：派发 `dataagent-plan-open` 事件，`detail` 为 `{markdown, title, body, requestId}`，可取消；宿主未调用 `preventDefault()` 时卡片原地展开。
- 复制：安全上下文用 `navigator.clipboard`，否则回退到 `textarea` 加 `execCommand`。
- 计划开关：
  - `composerConfig.planMode = {enabled, default}`；
  - 输入框左下角显示“💡 计划”按钮；
  - 打开时消息的 `settings.permission_mode` 为 `plan`，关闭时用权限下拉的值或 `default`。
- 请求块里带截止时间 `deadline_at`，回答块里带 `source`，流式解析和历史回放都保留，供界面显示倒计时和“自动选择”。
- 计划卡片是 `PlanCard`。`PermissionCard` 遇到 `risk_level=plan` 时转给它渲染，宿主不用改。

### T5 测试与验证

- Python：
  - 超时自动作答（有推荐、无推荐）；
  - 计划超时的 `approve` 与 `park`；
  - 修改意见传到模型；
  - Pi 交互帧的往返；
  - Agent 字段的校验。
- Pi：
  - `AskUserQuestion` / `ExitPlanMode` 的工具测试；
  - 计划模式拦截与批准后解除。
- 组件：问题卡片分页、跳过、自动提交；计划卡片的批准、调整、展开事件、复制回退；计划开关随消息发出。
- 端到端冒烟：本地起 MySQL、后端、DataAgent 前端，用 Pi 引擎跑一次带计划和提问的会话（会消耗模型调用，执行前确认）。

## 3. 发布与回退

- 迁移只加一列，默认值是 `park`，计划照旧一直等待。**行为变化**：问题卡片默认 10 分钟未回答就按推荐作答。想保持原来的无限等待，把 `dataagent_interaction_timeout_seconds` 设为 0。
- Pi 协议向后兼容：旧版宿主不发送 `permission_mode` 时按 `default` 处理，工具列表不变。
- 回退：还原代码即可，新增列保留无害。

## 4. 与 pi-coding-agent 迁移的关系

- 本计划的 T1（后端超时与修改意见）、T2（Agent 配置）、T4（界面）与运行时无关，照常推进。
- T3（Pi 引擎）的工具和中间转发层已经按可迁移的方式写成独立模块。迁移时，它们改为扩展注册，Python 侧协议不变，见迁移设计 4.4。

## 5. 不在本次范围

- PQ-06：等待期间释放 Cell 进程、从事件日志恢复运行。已记入 `docs/TODO.md`，随运行时迁移一起做。
