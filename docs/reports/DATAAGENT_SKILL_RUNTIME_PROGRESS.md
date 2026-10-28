# DataAgent Skill Runtime Progress

更新时间：2026-03-09

## 当前结论

- 智能问数真实产品路径应以流式 SSE 为主。
- 前端页面当前固定调用流式接口，见 [frontend/src/views/intelligence/NL2SqlChat.vue](~/.codex/worktrees/a56e/opendataworks/frontend/src/views/intelligence/NL2SqlChat.vue)。
- 后端非流式路径仍保留，但只作为契约校验、脚本调用和问题排查辅助，不作为主验收口径。

## 已完成

- `dataagent-nl2sql` skill 已重构为：
  - `SKILL.md`
  - `reference/`
  - `scripts/`
  - `assets/`
- skill 内容已切到 OpenDataWorks 实际场景，不再使用订单/金额语境。
- 多数据源能力已收敛到单次问答单源路由：
  - `MySQL`
  - `Doris`
- 图表能力已固定为：
  - `table`
  - `bar`
  - `line`
  - `pie`
- 前端问数页已只展示工具输出和图表，不再展示旧 SQL/结果卡片。
- 后端 loader / sync / admin store 已适配新 skill 目录结构。
- 本地前后端可直接启动并接通：
  - 前端：`127.0.0.1:3000`
  - 后端：`127.0.0.1:8900`

## 流式验收范围

当前用于真实回归的场景如下：

- 术语解释：`什么是数据表血缘？`
- 统计：`当前 active 状态的数据表数量`
- 对比：`各数据层表数量对比`
- 趋势：`最近 30 天工作流发布次数趋势`
- 占比：`各工作流发布操作类型占比`
- 明细：`最近工作流发布记录`
- 诊断：`查看 dwd_tech_dev_inspection_rule_cnt_di 的上下游血缘`
- SQL 示例：`给我一个查询最近 7 天工作流发布记录的 SQL 示例`

## 已验证结果

### 真实链路已通过

- 术语解释
  - 流式可返回最终自然语言结果。
- 统计
  - 流式可返回 `sql_execution`。
  - 实际观测到 `active` 状态数据表数量为 `773`。
- 对比
  - 流式可返回 `chart_spec`。
  - 图表类型已稳定为 `bar`。
- 趋势
  - 流式可返回 `chart_spec`。
  - 图表类型已稳定为 `line`。
- 占比
  - 流式可返回 `chart_spec`。
  - 图表类型已稳定为 `pie`。
- 明细
  - 流式可返回 `sql_execution`。
- SQL 示例
  - 流式可返回最终答案，且正文中包含可用 SQL 示例。

### 当前真实结论

- 后端真实流式回归已达成 `8/8` 通过。
- 报告已写入：
  - `docs/reports/live_nl2sql_stream_validation.json`
- 当前不再把“诊断场景不稳定”视为阻塞项；这轮真实流式回归中诊断场景已通过。

### 前端真实页面验证

- 已用真实浏览器验证页面主路径，不是只测接口。
- 已验证通过的页面场景：
  - `各工作流发布操作类型占比`
    - 页面成功渲染图表
  - `查看 dwd_tech_dev_inspection_rule_cnt_di 的上下游血缘`
    - 页面成功展示 `SQL 执行` 工具输出和正文分析结果
- 报告已写入：
  - `docs/reports/live_nl2sql_frontend_validation.json`

### 当前残留项

- 功能链路已通过，但仍有一个体验层待继续压缩的点：
  - 模型在流式执行过程中，仍可能先产生少量过程播报文本。
  - 不过最终落库和最终返回给前端的正文已经过后端清洗，不再保留这类过程播报。
  - 本轮已完成的压缩动作：
    - 前端已隐藏 `Glob` 这类低价值工具步骤
    - system prompt 和 `SKILL.md` 已增加“不要输出过程播报”的硬约束
    - 前端在存在工具输出时尽量延后正文展示
    - 后端在 `done` 前对最终用户可见正文做过程前缀清洗
  - 当前结论：这属于体验优化项，不再视为功能阻塞项。

## 已确认的真实数据事实

- `workflow_publish_record.operation` 当前有多个类别，可用于占比场景：
  - `deploy`
  - `online`
  - `offline`
- `workflow_publish_record.status` 当前只有 `success`，因此“最近失败的工作流发布记录”不适合作为默认验收题。
- `data_task.engine` 当前只有单一值，因此“各执行引擎任务数量占比”不适合作为默认验收题。
- `data_lineage` 中存在 `dwd_tech_dev_inspection_rule_cnt_di` 的血缘记录，可用于诊断场景。

## 本轮关键修正

- `build_chart_spec.py`
  - 默认比较场景不再误判为 `pie`。
  - 支持显式 `--chart-type`。
  - 兼容模型当前真实使用的旧参数形式。
- `SKILL.md` 与 `reference/*`
  - 强化“图表类型由 skill 明示，不由工具猜”。
  - 强化“显式血缘诊断题直接查平台 metadata/lineage，不要先搜仓库代码或文档”。
- `nl2sql_agent.py`
  - 系统提示词进一步精简，仅保留运行时硬约束。
  - 针对诊断类问题增加直接走平台元数据查询的硬规则。
- live 校验脚本
  - 默认模式改为 `stream`，与真实前端路径一致。

## 验证方式

- 单元与脚本测试
  - `pytest` 覆盖 `nl2sql_agent` 和 `build_chart_spec.py`
- 真实链路验证
  - 本地后端直接连真实 provider
  - 通过 `validate_live_nl2sql_scenarios.py` 逐题回归
- 前端验证
  - 本地 Vite 页面直连本地后端
  - 使用 Playwright 做实际页面检查

## 下一步

1. 若要继续做体验打磨，优先收“正文过程播报”而不是继续扩能力。
2. 功能层已经可以按生产验收口径收口。

## 备注

- 本地验证使用了用户提供的 AnyRouter 凭证。
- 凭证仅用于本地运行时，不写入仓库，不提交到 Git。
