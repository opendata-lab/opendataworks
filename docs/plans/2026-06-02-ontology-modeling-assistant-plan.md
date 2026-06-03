# 本体建模助手实施计划

> Design: [../design/2026-06-02-ontology-modeling-assistant-design.md](../design/2026-06-02-ontology-modeling-assistant-design.md)

## 任务 1：新增本体建模 skill 资产

- 创建 `dataagent/.claude/skills/ontology-modeling-assistant/`。
- 新增 `SKILL.md`，定义触发条件、边界、读取顺序、建模流程和交付要求。
- 新增 `assets/ontology.json`，沉淀本体建模元模型、对象关系、关系类型和查询函数。
- 新增 `reference/ontology-build-workflow.md`、`reference/output-contract.md`，其中 workflow 吸收 Anthropic Skill Creator 的意图捕获、测试提示词和 eval 迭代，但输出固定为领域本体 skill。
- 新增 `assets/ontology.schema.json`，由 Pydantic 模型导出。
- 新增 `scripts/ontology_schema.py`，定义本体文件 Pydantic 数据模型、结构规范和 `FIELD_DICTIONARY` 字段字典。
- 新增 `scripts/lookup_ontology.py`。
- 新增 `scripts/validate_ontology.py`，先执行 Pydantic 模型校验，再验证本体结构、关系端点、关系类型和查询函数契约。
- 新增 `tests/evals/evals.json`，提供本体建模 skill 的真实提示词样例。

## 任务 2：测试本体查找工具

- 新增 `dataagent/.claude/skills/ontology-modeling-assistant/tests/test_lookup_ontology.py`。
- 新增 `dataagent/.claude/skills/ontology-modeling-assistant/tests/test_validate_ontology.py`。
- 覆盖对象查询、关系查询、关系类型查询、关键词搜索、compact 输出、本体结构验证和坏引用报错。

## 任务 3：接入内置助手 profile

- 更新 `dataagent/dataagent-backend/core/agent_profile_service.py`，增加 `agent_ontology_modeling` 快照和 bootstrap 补齐逻辑。
- 新增 Alembic 迁移，upsert `本体建模助手`。
- 更新 `dataagent/dataagent-backend/core/skill_admin_service.py`，把 `ontology-modeling-assistant` 标记为 bundled skill，但不加入默认全局启用列表。

## 任务 4：补充回归测试

- 更新 `tests/test_agent_profile_service.py`，验证本体建模助手的工具、MCP 和 skill 绑定。
- 更新 `tests/test_builtin_skill_content.py`，验证本体建模 skill 资产存在且不包含 SQL 执行入口。
- 按需更新 skill 管理测试中对 bundled skill 的断言。

## 任务 5：验证

- `pytest dataagent/.claude/skills/ontology-modeling-assistant/tests/test_lookup_ontology.py`
- `pytest dataagent/.claude/skills/ontology-modeling-assistant/tests/test_validate_ontology.py`
- `pytest dataagent/dataagent-backend/tests/test_agent_profile_service.py dataagent/dataagent-backend/tests/test_builtin_skill_content.py dataagent/dataagent-backend/tests/test_skill_admin_service.py`
