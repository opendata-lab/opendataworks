# 输出契约

## 需求确认输出

```markdown
本体建模范围：
- 业务域与分级（核心/支撑/通用）：
- 域边界（包含/不包含）：
- 典型问题清单：
- 目标 skill：
- 核心对象：
- 关键关系：
- 排除项与排除原因：
- 需要的上传文档：
- 需要的数据库表/字段：
- 待确认口径：
```

## 领域 Skill 交付输出

```markdown
已生成领域本体 Skill：
- Skill 目录：
- 本体 JSON：
- JSON Schema：
- 本体查找脚本：
- 本体验证脚本：
- 核心对象：
- 核心关系：
- 关系类型：
- 验证：
- TODO：
```

## lookup 示例输出

```bash
python3 <skill>/scripts/lookup_ontology.py --query <业务词>
python3 <skill>/scripts/lookup_ontology.py --object <object_id> --include properties,functions,relations
python3 <skill>/scripts/lookup_ontology.py --relation <relation_id>
```

## validate 示例输出

```bash
python3 <skill>/scripts/validate_ontology.py --path <skill>/assets/ontology.json
python3 <skill>/scripts/validate_ontology.py --path <skill>/assets/ontology.json --json
python3 <skill>/scripts/validate_ontology.py --schema > <skill>/assets/ontology.schema.json
```

## 映射失败输出

```markdown
TODO：当前输入不足，不能确认该术语的本体映射。

候选解释：
- ...

最小需要补充：
- ...
```
