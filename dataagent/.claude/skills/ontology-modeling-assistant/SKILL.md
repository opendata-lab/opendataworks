---
name: ontology-modeling-assistant
description: "当用户需要根据业务需求、上传文档、数据库表字段或已有术语创建、补全、评审、迭代某个特定业务域的本体语义 Skill 时使用。用户提到本体建模、领域语义、业务对象、关系、relation_kind、指标口径、从文档和表生成 skill、查找本体工具时必须使用。"
tools: [Read, Bash, Glob, Grep, LS]
---

# Ontology Modeling Assistant Skill

本技能用于创建特定业务域的本体语义 Skill。它借鉴 Anthropic Skill Creator 的意图捕获、结构化交付和 eval 迭代方法，但输出被收窄为固定形态：领域本体 skill、`assets/ontology.json`、`object_relations`、`relation_kind`、本体查找脚本、索引和验证用例。

它不创建通用 skill，不负责 SQL 执行，不直接回答真实业务数据结果。需要发现平台表、字段、血缘或 DDL 时，只把建模输入发现需求交给 OpenDataWorks 平台工具链路。

## 目标交付

默认交付一个可安装的领域本体 skill 目录：

```text
<domain>-ontology/
  SKILL.md
  assets/ontology.json
  assets/ontology.schema.json
  reference/ontology-index.md
  reference/output-examples.md
  scripts/ontology_schema.py
  scripts/lookup_ontology.py
  scripts/validate_ontology.py
  tests/test_<domain>_lookup_ontology.py
  tests/test_<domain>_validate_ontology.py
  tests/evals/evals.json
```

测试文件名必须带领域前缀：多个 skill 的同名测试模块会在同一次 pytest 运行中产生导入冲突。

`assets/ontology.json` 顶层只包含 `metadata`、`object_types`、`object_relations`，其中：

- `object_types`: 业务域、实体、属性、指标、物理表、上传文档、领域 skill 等对象。
- `object_relations`: 统一关系集合，例如实体拥有属性、实体依赖实体、指标归属于实体、术语映射字段、文档提及实体、表字段支撑属性、指标由字段计算。
- `relation_kind`: 关系类型，例如 `has_attribute`、`supports`、`semantic_mapping`、`caliber_rule`。
- `query_functions`: 本体查询交接函数，用于后续问数链路确认语义，不直接执行 SQL。

## 工作流

1. 读 [`reference/ontology-build-workflow.md`](reference/ontology-build-workflow.md)，按意图访谈、建模输入收集、域划分与本体识别、建模、skill 交付和验证推进。划分业务域、判断哪些概念该建成本体对象和关系时，按 [`reference/ontology-scoping-method.md`](reference/ontology-scoping-method.md) 执行。
2. 用 [`scripts/lookup_ontology.py`](scripts/lookup_ontology.py) 查询本技能的建模元模型，确认应该创建哪些对象、关系和 `relation_kind`：

```bash
python3 dataagent/.claude/skills/ontology-modeling-assistant/scripts/lookup_ontology.py --query 上传文档
python3 dataagent/.claude/skills/ontology-modeling-assistant/scripts/lookup_ontology.py --object domain_entity --include properties,functions
python3 dataagent/.claude/skills/ontology-modeling-assistant/scripts/lookup_ontology.py --relation term_maps_to_table_column
python3 dataagent/.claude/skills/ontology-modeling-assistant/scripts/validate_ontology.py --path dataagent/.claude/skills/ontology-modeling-assistant/assets/ontology.json
```

3. 按 `scripts/ontology_schema.py` 的 Pydantic 模型和 `FIELD_DICTIONARY` 生成目标领域的 `assets/ontology.json`。
4. 用 `scripts/ontology_schema.py` 的 Pydantic 模型导出 `assets/ontology.schema.json`；字段字典通过 schema 的 `x-field-dictionary` 暴露。
5. 用 `validate_ontology.py` 校验生成的 `assets/ontology.json`。
6. 按 [`reference/output-contract.md`](reference/output-contract.md) 输出或写入目标领域 skill。

## 建模规则

- 先划分业务域并分级（核心/支撑/通用），再识别对象；一个领域 skill 只覆盖一个主业务域。
- 用 5~15 个典型问题收敛建模范围；每个本体对象和关系必须能支撑至少一个典型问题，支撑不了的候选登记为排除项。
- 文档和表字段是建模输入，不等同于本体。需要把自然语言术语、物理字段和业务对象分层表达。
- 不确定时标记 `TODO` 和 `needs_confirmation`，不要伪造口径。
- 所有边都放在 `object_relations`；术语到字段、文档到对象、字段到属性的关系也用 `relation_kind` 表达，不再单独建 `semantic_edges`。
- 字段可选值必须集中登记在 `scripts/ontology_schema.py` 的 `FIELD_DICTIONARY` 里；新增 `relation_kind` 等枚举值时，重新生成目标领域 skill 的 schema。
- 本体 JSON 必须能通过 Pydantic 数据模型和 JSON Schema 校验；不得只依赖人工检查。
- 查询函数是语义交接契约，包含 intent、grain、params、output_fields 和 notes；不得把它当成可直接运行的 SQL。
- 生成的领域 skill 必须自带 `lookup_ontology.py`，让后续助手按小片段查询，不直接读取整份本体 JSON。
- 生成的领域 skill 必须自带 `validate_ontology.py`，验证 JSON 结构、id 唯一性、关系端点、`relation_kind` 和查询函数契约。

## 输出要求

面向用户时先给建模结论，再给缺口。

- 需求足够：说明将生成的领域 skill 名称、核心对象、关系类型、建模输入和验证方式。
- 输入不足：列出最小缺口，例如需要哪些上传文档、哪些表/字段、哪些口径确认。
- 交付完成：列出新增文件、lookup 示例、测试命令和仍待人工确认的 TODO。

不要输出完整 SQL，不要暴露数据库账号密码，不要把尚未确认的术语写成确定本体。
