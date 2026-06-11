# 本体建模助手设计

## 当前状态

DataAgent 已支持内置助手 profile。助手通过 `skill_folders` 选择可用 skill，运行时只把选中的 skill 链接到隔离工作目录。现有 `arch-governance-assistant` 是仓库根目录下的领域本体示例，包含 `assets/ontology.json`、本体引用说明、查找脚本和脚本测试。Anthropic `skill-creator` 提供了从意图访谈、skill 草稿、测试提示词到 eval 迭代的通用创建闭环。DataAgent 内置业务 skill 位于 `dataagent/.claude/skills/`，这是助手运行时的默认发现根。

## 问题

用户需要一个专门用于本体建模的助手：根据建模需求、用户上传文档和平台数据库表，生成某个业务域的本体语义 skill。这个 skill 需要能沉淀结构化本体 JSON、对象关系、关系类型、查询函数和查找工具，供后续问数或语义解释链路使用。

## 范围

本次新增：

- DataAgent 内置 skill：`ontology-modeling-assistant`。
- skill 资产：`SKILL.md`、`assets/ontology.json`、`reference/*`、`scripts/lookup_ontology.py`。
- 脚本测试：验证对象、关系、关系类型和关键词查询。
- DataAgent 内置助手：`agent_ontology_modeling`，名称为 `本体建模助手`，只绑定本体建模 skill 和安全读写工具。
- Alembic 迁移：为已有部署补齐内置助手 profile。

不在本次新增：

- 不接入新的前端页面或表单。
- 不新增数据库业务表。
- 不改变通用问数、SQL 执行、平台工具或模型运行链路。
- 不自动生成某个具体业务域的完整本体。新 skill 提供建模流程、模板本体和查找工具，具体业务域本体由后续用户输入驱动生成。

## 方案

新增 `dataagent/.claude/skills/ontology-modeling-assistant/`，采用和 `arch-governance-assistant` 相近的目录结构，并吸收 `skill-creator` 的创建闭环，但定位收窄为“领域本体 skill 创建器”：

- `SKILL.md` 约束助手先收集业务目标、上传文档和候选物理表，再输出可交付的领域本体 skill。
- `assets/ontology.json` 保存本体建模元模型，描述业务域、实体、属性、指标、关系、关系类型和查询函数等对象类型。
- `reference/ontology-build-workflow.md` 定义从需求、文档和表字段到本体的建模流程，包含意图捕获、草稿、测试提示词和 eval 迭代。
- `reference/output-contract.md` 定义交付物格式。
- `scripts/lookup_ontology.py` 从本体 JSON 中按对象、关系、关系类型或关键词输出小片段，避免把整份 JSON 一次性塞给模型。
- `scripts/ontology_schema.py` 定义 Pydantic 本体数据模型、结构规范和 `FIELD_DICTIONARY` 字段字典，并导出带 `x-field-dictionary` 的 JSON Schema。
- `scripts/validate_ontology.py` 先执行 Pydantic 数据模型校验，再验证本体结构、id 唯一性、关系端点、关系类型和查询函数契约。

新增助手 profile：

- `agent_id`: `agent_ontology_modeling`
- `name`: `本体建模助手`
- `skill_folders`: `["ontology-modeling-assistant"]`
- `allowed_tools`: `["Skill", "Bash", "Read", "LS", "Glob", "Grep"]`
- `mcp_server_ids`: `["portal"]`

`portal` 用于后续通过平台工具发现表、字段、血缘和只读元数据。助手本身只负责建模和交付语义资产，不执行真实业务数据分析。

## 接口与兼容

新增 profile 不改变现有 `agent_default` 和 `agent_opendataworks`。新迁移只 upsert `agent_ontology_modeling`，不会改写历史 topic 或 task 的 agent 绑定。`bootstrap_default_agent_profile()` 在运行时也会补齐新内置助手，覆盖未执行迁移但服务已启动的本地开发场景。

## 验证

- 运行本体建模 skill 的 `lookup_ontology.py` 聚焦测试。
- 运行本体建模 skill 的 `validate_ontology.py` 聚焦测试。
- 运行 `tests/test_agent_profile_service.py` 验证内置助手快照。
- 运行 `tests/test_builtin_skill_content.py` 验证内置 skill 内容边界。

本次不做完整 DataAgent HTTP smoke，因为改动不改变任务执行链路和前端交互。
