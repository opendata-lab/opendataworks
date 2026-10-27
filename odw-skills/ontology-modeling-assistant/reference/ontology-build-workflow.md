# 本体语义 Skill 创建工作流

## 1. 捕获意图

先从对话中抽取，缺口再追问：

- 业务域名称和目标用户。
- 这个本体要支持的典型问题，例如解释术语、找表、问指标、追踪关系、生成查询交接。
- 用户上传文档的范围和可信度。
- 候选数据库、表、字段、血缘或 DDL。
- 期望输出：仅本体 JSON、完整 skill 目录，还是对已有领域 skill 迭代。

## 2. 收集建模输入

把输入分成四类：

- 用户直接说出的建模目标、边界和术语。
- 上传文档中的定义、流程、规则、指标口径。
- 数据库表、字段、注释、主键、分区、血缘。
- 用户对歧义、口径和命名的确认。

本体 JSON 不保存来源索引或旧版来源字段。术语映射、文档提及、字段支撑和口径规则都建模为 `object_relations`，通过 `relation_kind` 区分。

## 3. 划分域与识别建模对象

按 [`ontology-scoping-method.md`](ontology-scoping-method.md) 执行四步：划分业务域、用典型问题收敛范围、识别本体对象、识别本体关系。产出域划分结论、典型问题清单、候选对象清单和候选关系清单。

候选清单未产出前不要进入草案生成。支撑不了任何典型问题的候选对象和关系直接登记为排除项。

## 4. 生成本体草案

以第 3 步的候选清单为准，先建核心对象，再补关系：

1. `business_domain`
2. `source_document`
3. `physical_table`
4. `domain_entity`
5. `domain_attribute`
6. `domain_metric`
7. `semantic_relation`
8. `domain_query_function`
9. `domain_ontology_skill`

不要直接从表名生成实体名。先看文档定义和字段注释，再用表字段支撑实体属性。

## 5. 生成领域 Skill

领域 skill 目录应包含：

- `SKILL.md`: 触发条件、边界、读取顺序、输出要求。
- `assets/ontology.json`: 结构化本体、对象关系、关系类型和查询函数。
- `assets/ontology.schema.json`: 由 Pydantic 模型生成的 JSON Schema。
- `reference/ontology-index.md`: 对象、关系和 `relation_kind` 索引。
- `scripts/ontology_schema.py`: Pydantic 数据模型、JSON Schema 和 `FIELD_DICTIONARY` 字段字典来源。
- `scripts/lookup_ontology.py`: 小片段本体查找工具。
- `scripts/validate_ontology.py`: 本体结构和引用完整性验证工具。
- `tests/test_<domain>_lookup_ontology.py`: 确认核心对象、关系和 `relation_kind` 可查。
- `tests/test_<domain>_validate_ontology.py`: 确认本体 JSON 结构、关系端点和字段枚举有效。测试文件名带领域前缀，避免和其他 skill 的同名测试模块在同一次 pytest 运行中冲突。
- `tests/evals/evals.json`: 2-3 个真实用户提示词，用于验证 skill 是否能触发并交付正确语义。

## 6. 验证和迭代

采用收窄版 Skill Creator 闭环：

- 先写 deterministic 测试：JSON 可解析、lookup 可查核心对象、关系和 `relation_kind`。
- 每次交付前运行 `validate_ontology.py --path assets/ontology.json`，失败时先修复本体再继续。该脚本先执行 Pydantic 数据模型校验，再执行跨对象引用校验。
- 每次修改 Pydantic 模型后运行 `validate_ontology.py --schema > assets/ontology.schema.json`，并确认 schema 文件同步。
- 每次修改 `scripts/ontology_schema.py` 中的 `FIELD_DICTIONARY` 后运行 `validate_ontology.py --schema > assets/ontology.schema.json`，确认 schema 的 `x-field-dictionary` 同步。
- 新增枚举值时先更新 `scripts/ontology_schema.py` 中的字段字典，再更新本体 JSON。
- 交付领域 skill 前运行 `python -m pytest <skill>/tests -q`。DataAgent 后端和 sandbox runner 镜像预装 `pytest`，可直接用于生成后验证。
- 再写 eval prompts：覆盖术语解释、关系查询、真实问数语义交接。
- 每轮迭代后对照典型问题清单做反向验收，不能表达的问题登记 TODO。
- 对照用户反馈迭代 `description`、本体索引和输出示例。
- 每轮迭代后重新运行 lookup 测试。

如果用户只需要快速草案，可以先交付本体和 TODO，但必须显式说明哪些输入或口径还未确认。
