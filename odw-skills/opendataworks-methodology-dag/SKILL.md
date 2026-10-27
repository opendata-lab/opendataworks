---
name: opendataworks-methodology-dag
description: "检索与校验固化业务口径的多步分析方法论，并通过 OpenDataWorks MCP 执行查询节点。"
tools: [Read, Bash]
---

# 方法论 DAG

先调用本技能的 lookup_methodology.py 检索注册表；未命中则回到常规 MCP 问数。

```bash
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/opendataworks-methodology-dag/scripts/lookup_methodology.py" --query "<问题>"
```

命中后读取 assets/registry/ 中的工件，确认口径和必填参数，按依赖顺序绑定 SQL。
所有 sql 节点只调用 `odw_query_readonly`，不在本地执行数据库访问；共享依赖只查询一次。
合并、条件与计算节点基于真实工具结果在当前工作区处理。返回业务口径、结果与证据。
失败或空结果时按真实原因说明，不改变方法论重复试探。

静态校验：

```bash
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/opendataworks-methodology-dag/scripts/validate_methodology.py" --all
```

工件模型见 reference/10-model.md，绑定规则见 reference/20-authoring.md。
