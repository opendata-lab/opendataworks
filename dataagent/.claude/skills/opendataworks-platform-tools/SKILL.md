---
name: opendataworks-platform-tools
description: "当请求需要真实 OpenDataWorks 平台能力时使用：元数据查询、表/字段发现、数据源路由、血缘、DDL、只读 SQL 验证/执行、结果格式化或图表契约输出。不用于业务语义或 NL2SQL 推理。"
compatibility: "需要 DATAAGENT_PYTHON_BIN、DATAAGENT_PLATFORM_SKILL_ROOT，以及可见的 portal MCP 工具或本技能 bin/odw-cli 的后端服务访问能力。"
tools: [Bash, Read]
---

# OpenDataWorks 平台工具技能

OpenDataWorks Platform Tools Skill。平台工具 Skill。

这是 OpenDataWorks 平台工具技能，提供真实平台能力：获取表、获取字段、获取血缘、获取 DDL、解析数据源、验证 SQL、执行只读 SQL、格式化结果和生成图表契约。

它不定义业务术语、本体、指标口径、别名、歧义规则或查询方法。业务含义交给语义技能，SQL 就绪规则由 DataAgent system prompt 约束。只有需要真实 OpenDataWorks 平台证据或执行时，才使用本技能。

## 范围

负责：

- 元数据搜索，以及候选表或字段检查。
- 数据源路由和引擎/database 解析。
- 表 DDL 和字段详情查询。
- 血缘查询。
- 只读 SQL 验证。
- 通过 backend 平台 API 执行只读 SQL。
- SQL 执行结果格式化。
- `sql_execution` 和 `chart_spec` 工具输出契约。
- 优先 MCP，MCP 不可用时才回退脚本。

不负责：

- 业务术语或指标定义。
- 对象本体和语义映射。
- 领域别名和歧义规则。
- 通用 NL2SQL 推理方法。
- 判断 SQL 语义是否就绪；调用方必须提供已确认 SQL，或明确的元数据/DDL/血缘/数据源请求。
- 租户私有知识。

## 硬规则

1. 当前运行中能看到 portal MCP 工具时，优先使用 portal MCP。
2. 只有 portal MCP 不可用时，才使用脚本回退。
3. 脚本回退必须使用：
   `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...`
4. 平台脚本不要使用 primary `DATAAGENT_SKILL_ROOT`。
5. 脚本回退执行前，必须先验证已确认 SQL。
6. 已确认 SQL 必须通过唯一只读 SQL 执行入口执行。
7. 不执行 `INSERT`、`UPDATE`、`DELETE`、`DROP`、`TRUNCATE`、`ALTER`、`CREATE`、`REPLACE` 或其他写语句。
8. `SELECT` 查询默认带 `LIMIT`，除非已验证的语句类型不支持。
9. 不暴露数据源账号密码或直连数据库细节。
10. 首个足够平台结果或不可重试失败归因出现后就停止。
11. 大结果或要落盘的场景，必须用导出脚本 `export_query.py`，让全量数据写工作区文件、只把路径与预览回给模型；不要用 `portal_query_readonly` 或 `run_sql.py` 把全量结果拉进上下文（会被结果字节守卫截断，且可能撑爆运行时缓冲）。

## 读取顺序

1. 读 [`reference/30-tool-recipes.md`](reference/30-tool-recipes.md)，确认具体平台能力路径。
2. 运行时环境或根变量不清楚时，读 [`reference/40-runtime-metadata.md`](reference/40-runtime-metadata.md)。
3. 解释验证、执行或图表输出时，读 [`reference/50-tool-output-contract.md`](reference/50-tool-output-contract.md)。
4. 只有 recipe 需要且引用文档没有写明精确参数时，才查看脚本。

## 能力地图

| 能力 | 使用场景 | 优先路径 |
| --- | --- | --- |
| 获取表 / 获取字段 | 需要候选表、相似表、表注释、字段名或字段注释 | portal MCP 元数据搜索，失败再脚本回退 |
| 获取血缘 | 需要上下游证据或血缘诊断 | portal MCP 血缘查询，失败再脚本回退 |
| 获取 DDL | 需要实时表结构、字段顺序、分区、注释或建表语句 | portal MCP DDL 查询，失败再脚本回退 |
| 解析数据源 | SQL 执行前需要执行引擎/database 路由 | portal MCP 数据源解析，失败再脚本回退 |
| 验证 SQL | SQL 已就绪，准备脚本回退执行 | 验证脚本 |
| 执行只读 SQL | SQL 已验证通过，且需要真实结果 | 只读 SQL 执行脚本 |
| 导出结果到文件 | 结果行数多、或需落盘供后续 Python 处理（如生成 Excel） | 导出脚本 `export_query.py`，全量写工作区 CSV，只回路径+预览 |
| 图表契约 | SQL 结果形态适合前端图表渲染 | 图表契约脚本 |

## 最终输出

基于结构化平台结果返回中文结论。不要编造平台数据。平台工具返回不可重试错误时，报告失败归因并停止，不要猜另一张表或另一个字段。

需要交付给用户、可下载的产物（导出 CSV、Excel、HTML 报告、图片等）一律写工作区的 `output/` 目录；只有 `output/` 与 `uploads/` 下的文件会出现在会话文件面板并可下载。中间草稿可写工作区其它位置，但不会展示给用户。
