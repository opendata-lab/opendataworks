---
name: opendataworks-methodology-dag
description: "当问数请求命中一个已固化口径的多步分析方法论时使用：环比/同比增长、Top-N 后再取数、跨引擎结果合并、按条件切换统计口径。提供方法论检索、静态校验与一次性执行。不用于临时探索型问数、语义建模或数据开发。"
tools: [Read, Bash, Glob, Grep]
---

# OpenDataWorks Methodology DAG Skill

本技能把「多步分析方法论」从模型每次现场重写的 SQL，变成**注册表里的声明式工件**：
一张有向无环图，节点是带类型的执行步骤，边是数据依赖。执行由引擎负责——按需求值、
共享依赖只算一次、独立分支自动并行、未选中的条件分支永不执行。

它解决两件事：

- **口径不再漂移**。同一个业务问题每次都走同一张图、同一套口径，结果可复核。
- **一次调用替代多轮往返**。依赖查询、结果合并、条件切换都在一次工具调用里完成。

它不做语义建模，不做数据开发，不替代常规问数链路。**未命中注册方法论时必须回落。**

## 前置依赖

本技能的 `sql` 节点通过 `opendataworks-platform-tools` 的 `run_sql.py` 执行只读查询，
以复用平台既有的只读校验、数据范围校验和失败归因。脚本自己定位它：取同级目录
`../opendataworks-platform-tools`，不需要宿主注入任何环境变量。

**必须与 `opendataworks-platform-tools` 一起安装并启用。** 两者都不存在时执行会返回
`error_code=platform_tools_unavailable`；此时说明缺少执行入口，不要改用其他方式取数。

## 脚本怎么调

所有脚本都在本技能目录的 `scripts/` 下，**路径一律相对本 SKILL.md 所在目录**。
先切到本技能目录再执行，不要把仓库路径、部署路径或工作区路径写死到命令里：

```bash
cd <本技能目录> && python3 scripts/<name>.py ...
```

`<本技能目录>` 就是你读到这份 SKILL.md 的那个目录。脚本自身会解析它自己的位置
（注册表、schema、同级技能都由脚本自行定位），所以只要能进到这个目录，命令就能跑。

## Playbook

1. **先检索，再决定**。拿到用户问题后先查注册表：

   ```bash
   cd <本技能目录> && python3 scripts/lookup_methodology.py --query "<用户问题>"
   ```

   返回每个候选的 `intent`、`caliber`、参数槽位和输出字段。**这一步不执行任何查询。**

2. **命中且参数齐全 → 一次执行拿最终结果**：

   ```bash
   cd <本技能目录> && python3 scripts/run_methodology.py --id <id> --params '{"days":30}'
   ```

   输出契约与 `run_sql.py` 一致（`kind=sql_execution`），可直接收口回答，也可直接喂给
   `chart-visualization` 技能出图。**不要再逐节点自己拼 SQL 重跑一遍。**

3. **参数缺失** → 返回 `error_code=param_missing` 并列出缺的槽位。
   只追问这些槽位，不要自行假定口径、不要用默认值蒙混。

4. **未命中** → 返回 `matched=0`。回落到 `opendataworks-platform-tools` 的常规链路
   （语义确认 → SQL 生成 → `validate_sql.py` → `run_sql.py`）。这是一等路径，不是错误。

5. **回答时必须说明口径**。方法论输出里的 `methodology.caliber` 就是这次统计的口径，
   连同参数一起写进回答。

## 硬性规则

- 命中方法论后**不得**为了"跑得更快"或"结果更好看"绕过它自己写 SQL。
  绕过就是口径漂移，正是本技能要消除的东西。
- 不得为了让某次运行通过而临时修改注册表里的 `caliber`、`sql` 或参数默认值。
  口径变更是一次独立的评审动作，必须同时升 `version`。
- 不得猜测方法论 `id`。只用 `lookup_methodology.py` 返回的 id。
- 不得把 `run_methodology.py` 当成通用 SQL 执行器；它只跑注册表里的图。
  临时 SQL 仍然走 `run_sql.py`。
- 执行返回 `result_state=empty_result` 时，说明口径下确实无数据，
  解释口径与空结果即可，**不要换方法论或改参数反复试探**。
- 执行返回 `result_state=failed` 时，按 `error_code`、`failure_attribution`、
  `stop_reason` 说明原因，不要等价重试。

## 参考文档

| 文档 | 用途 |
|---|---|
| [`reference/10-model.md`](reference/10-model.md) | 节点类型、依赖语义、求值规则 |
| [`reference/20-authoring.md`](reference/20-authoring.md) | 怎么写一个新方法论，含三种典型结构 |
| [`reference/30-invocation.md`](reference/30-invocation.md) | 三个脚本的完整调用契约，含 mock 模式 |
| [`reference/40-output-contract.md`](reference/40-output-contract.md) | 输出 JSON 与失败归因 |

## 与其他技能的分工

- `opendataworks-business-knowledge`：**单表达式指标**（`metrics.json` 的 `metric_key` /
  `formula`）继续留在那里。只有需要多步、依赖查询或跨引擎的才升级成方法论。
- `ontology-modeling-assistant`：本体的 `query_functions` 声明**语义契约**
  （intent / grain / params / output_fields），本技能的工件提供**可执行体**，
  两者通过 `ontology_ref.function_name` 关联。语义在上、执行在下，不要混写。
- `opendataworks-platform-tools`：SQL 验证、SQL 执行、元数据发现全部走它。
  本技能不复制这些能力。
- `chart-visualization`：结果需要出图时走它，调用方式见其 SKILL.md。
