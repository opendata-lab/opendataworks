# 方法论模型

先结论：一个方法论是一张**有向无环图**。节点是带类型的执行步骤，边是数据依赖，
恰好一个节点被指定为 `target`。执行从 `target` 出发，只走到它的传递依赖为止。

## 顶层结构

```json
{
  "id": "table_growth_ratio",
  "version": "1.0.0",
  "name_zh": "数据表分层环比增长",
  "intent": "本方法论回答什么问题",
  "caliber": "统计口径：时间字段、区间、过滤与排除规则",
  "owner": "口径责任人",
  "synonyms": ["检索用同义词"],
  "output_fields": ["target 节点预期输出列"],
  "ontology_ref": {"skill": "...", "function_name": "..."},
  "params": [ /* 参数槽位声明 */ ],
  "nodes": { /* 节点名 -> 节点定义 */ },
  "target": "growth"
}
```

平台访问只使用已配置的 MCP 工具，见 opendataworks-platform-tools/SKILL.md。
- `caliber` 不是注释，是**契约**：它会随执行结果一起回给用户。改口径必须同时升 `version`。
- `ontology_ref.function_name` 指向本体的 `query_functions`。语义在本体、执行在这里。

## 节点类型

节点分三组，每个节点消费并产出一张表（`columns` + `rows`）。

### query 组

| type | 说明 |
|---|---|
平台访问只使用已配置的 MCP 工具，见 opendataworks-platform-tools/SKILL.md。

字段：`database`（必填）、`sql`（必填）、`engine`（`mysql`/`doris`，可选）、
`predicates`（谓词片段声明，可选）、`limit`（可选，默认取 `DATAAGENT_QUERY_LIMIT`）。

### compute 组

| type | 说明 |
|---|---|
| `sqlite` | 把依赖节点结果按节点名建表装进**内存 SQLite**，再执行完整 SQL。这是做 join、集合运算、以及**跨引擎结果合并**的通路。 |
| `transform` | 对**单个**依赖结果做行级过滤、派生列、改名、投影、排序、截断。 |
| `literal` | 固定行集合，例如维度到列名的映射表。 |

`sqlite` 字段：`dependencies`（至少一个）、`sql`、`primary_keys`（可选，为大输入建索引）。
`FROM` 子句里的表名就是依赖节点名。

`transform` 字段：`dependencies`（恰好一个）、`filter`、`derive`、`rename`、`select`、
`sort`、`limit`。`derive` 按声明顺序求值，后面的表达式可以引用前面刚派生出的列。

### control 组

| type | 说明 |
|---|---|
| `conditional` | 先求 `when` 谓词，再解析二选一分支。**未选中的分支及其整个子图永不执行。** |
| `call` | 调用注册表中的另一个方法论，是复用单元。调用链禁止成环。 |

`conditional` 字段：`when`、`then_node`、`else_node`，以及求谓词所需的 `dependencies`。
分支是**软依赖**：它们不参与预先的依赖解析，所以未取分支代价为零。

`call` 字段：`methodology_id`、`params`（子方法论参数名 → 在本地上下文求值的表达式）。

## 求值规则

1. **只构建需要的**。从 `target` 不可达的节点永不执行；条件未选中分支及其子图也不执行。
2. **最多构建一次**。每个节点的结果在一次运行内被 memoize。被多个节点依赖的查询只打一次库。
3. **并行是声明出来的**。相互独立的依赖会被同时求值，方法论作者不写任何并发代码。
4. **两级超时**。整次运行有总预算（默认 240s），单个节点另有超时（默认取
   `DATAAGENT_SQL_READ_TIMEOUT_SECONDS`）。总预算耗尽时报 `methodology_timeout`。

## 表达式上下文

表达式出现在占位符、`transform` 的 `filter`/`derive`、`conditional` 的 `when`、
`call` 的 `params`，以及谓词的 `$` 引用里。可见的名字只有：

| 名字 | 内容 |
|---|---|
| `params` | 本次运行解析后的参数，例如 `params.days` |
| `<依赖节点名>` | `{"columns": [...], "rows": [...], "row_count": n}`，例如 `current.rows` |
| `row` | 仅 `transform` 内可见，当前行，例如 `row.publish_cnt` |

可用函数（白名单，别的都不允许）：
`pluck`、`coalesce`、`distinct`、`len`、`abs`、`round`、`min`、`max`、`sum`、
`int`、`float`、`str`、`lower`、`upper`。

`pluck(current.rows, 'layer')` 取出一列并丢弃 null，是构造 `IN` 列表的标准做法。

表达式由受限的 AST 求值器执行：**不使用 `eval`/`exec`**，属性访问只走字典查找而非
`getattr`，下划线开头的字段名被拒绝。因此表达式无法触达任何 Python 对象内部。

## SQL 占位符

`sql` 与 `sqlite` 节点的 `sql` 是模板，有三种占位符，各自的安全契约不同：

| 形式 | 名称 | 契约 |
|---|---|---|
| `{{ expr }}` | 受检值 | 求值结果必须是标量或标量数组。标量转义后加引号，数组展开为逗号列表。**值无法逃出它的字面量位置。** |
| `{{! expr }}` | 标识符片段 | 原样拼接，因此只接受合法标识符（列名、别名），或参数 `values` 枚举中的取值。其余一律拒绝。 |
| `{{? name }}` | 谓词片段 | 引用本节点 `predicates` 里的同名谓词。 |

平台访问只使用已配置的 MCP 工具，见 opendataworks-platform-tools/SKILL.md。
任何一层都不替代另一层。

## 谓词 DSL

用来从**可选参数**拼 `WHERE`，避免在模板里写分支。

```json
"predicates": {
  "layer_filter": { "op": "eq", "field": "layer", "value": "$params.layer" }
}
```

- `value` / `values` 以 `$` 开头时按表达式求值，否则当字面量。
- 运算符：`eq` `ne` `lt` `lte` `gt` `gte` `like` `in` `between`，
  以及组合运算符 `and` `or` `not`（用 `clauses` 承载子谓词）。
- **求值为 null 的子谓词被丢弃**；全部为空时整个片段渲染成空串。
  参数没传，过滤条件就自然消失——这就是这个 DSL 存在的理由。
- `in` 的 `values` 可以是 `"$pluck(current.rows, 'layer')"`，空列表同样被丢弃，
  所以上游返回 0 行时下游不会拼出非法的 `IN ()`。

## 静态校验

执行前，`validate_methodology.py` 会拒绝：`target` 不存在、依赖引用不存在、节点自依赖、
依赖图成环（**报出具体环路径**）、占位符形式无法识别、表达式引用未知名字或未声明参数、
引用未声明的谓词片段、枚举参数没有 `values`、`call` 目标不存在或调用图成环。

在手写胶水里本该是运行时异常的错误，在这里是加载期拒绝。
