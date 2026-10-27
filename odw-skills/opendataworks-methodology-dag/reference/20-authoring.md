# 编写一个方法论

先结论：先把口径写清楚，再画依赖，最后才写 SQL。工件放在
`assets/registry/<id>.json`，一个文件一个方法论。

## 什么该做成方法论

| 情况 | 归属 |
|---|---|
| 单个聚合表达式（`COUNT(...)`、`SUM(CASE WHEN ...)`）| 留在 `opendataworks-business-knowledge` 的 `metrics.json` |
| 一条 SQL 能算完，口径稳定，但反复被问 | 可以做成方法论，但收益主要是口径固化 |
| 需要**依赖查询**（先取 Top-N 再按结果二次查询）| 方法论 |
| 需要**合并多个查询结果**（环比、同比、多口径对比）| 方法论 |
| 需要**跨引擎**（MySQL 结果与 Doris 结果合并）| 方法论（`sqlite` 节点） |
| 需要**按条件切换口径**（有无过滤条件走不同表）| 方法论（`conditional` 节点） |
| 一次性探索、口径尚未确定 | 不要做成方法论，走常规问数链路 |

判断标准就一条：**这个口径是不是需要被多次问、且每次都必须一致。** 是就固化，否就别固化。

## 步骤

1. **写 `intent` 和 `caliber`**。`caliber` 会随结果回给用户，所以必须写明：
   用哪个时间字段、区间怎么取、排除了什么、什么情况下行会被剔除。
   写不清楚说明口径还没确定，不要往下走。
2. **声明参数槽位**。凡是会变的都做成参数：时间窗口、维度过滤、Top-N 的 N。
   可选参数一律配谓词片段，不要在模板里写 `if`。
3. **画依赖**。先想清楚哪些查询互相独立（会并行）、哪些有先后（会串行）、
   哪个中间结果被多处引用（只会算一次）。
4. **写节点**。查询用 `sql`，合并用 `sqlite`，补算列用 `transform`，切换口径用 `conditional`。
5. **校验**：
   ```bash
   python3 scripts/validate_methodology.py --path assets/registry/<id>.json
   ```
6. **写 mock 用例并跑通**。见 [`30-invocation.md`](30-invocation.md) 的 mock 模式。
   方法论应该能在不碰数据库的情况下被断言，这是它相对手写 SQL 最大的优势之一。

## 三种典型结构

### 一、链式依赖 + 内存 join（环比 / 同比）

`table_growth_ratio` 是这个形态。第二次查询用第一次的结果收敛范围，
再把两期结果 join 起来算比率。

```json
"previous": {
  "type": "sql",
  "dependencies": ["current"],
  "predicates": {
    "layer_scope": { "op": "in", "field": "layer", "values": "$pluck(current.rows, 'layer')" }
  },
  "sql": "SELECT layer, COUNT(id) AS table_cnt FROM opendataworks.data_table WHERE ... {{? layer_scope }} GROUP BY layer"
}
```

注意 `current` 同时被 `previous` 和 `growth` 依赖——引擎只会执行它**一次**。
这是选择声明式依赖而不是手写编排的直接收益。

用 `in` 谓词而不是 `IN ({{ pluck(...) }})`：上游返回 0 行时，谓词整段消失，
而受检值占位符会因为空列表报错。

### 二、Top-N 后再取数

`top_owner_task_growth` 是这个形态。第一条查询选出实体，第二条查询只针对这些实体。

关键点是第二条查询的范围必须由第一条的结果收敛，否则就退化成"全表扫完再过滤"，
方法论也就没有意义了。

### 三、条件剪枝

`workflow_publish_trend` 是这个形态。参数传了就查过滤口径，没传就查全量口径。

```json
"pick": {
  "type": "conditional",
  "when": "params.operation == None",
  "then_node": "publish_all",
  "else_node": "publish_scoped"
}
```

未选中的那条分支及其整个子图**不会执行**。什么时候用 `conditional`、什么时候用谓词片段：

- 只是多一个 `AND` 条件 → 用谓词片段，一个节点搞定。
- 两个口径查的是**不同的表**或**不同的聚合方式** → 用 `conditional`。

## 复用：`call` 节点

当一段口径会被多个方法论共用时，把它单独注册成一个方法论，其他方法论用 `call` 调它。

```json
"base": {
  "type": "call",
  "methodology_id": "table_growth_ratio",
  "params": { "days": "params.window_days" }
}
```

这是防漂移最强的机制：两个方法论都 `call` 同一张图，它们的定义就不可能悄悄分叉。
调用图必须无环，校验器会拒绝环。

## 常见错误

- **把口径写进注释而不是 `caliber`**。注释不会回给用户，`caliber` 会。
- **用 `{{! }}` 拼用户输入**。片段占位符只给列名/别名用。任何来自用户的值都走 `{{ }}` 或谓词。
- **表名不带 schema 前缀**。`sql` 节点必须写 `opendataworks.data_table`，
平台访问只使用已配置的 MCP 工具，见 opendataworks-platform-tools/SKILL.md。
  （`sqlite` 节点相反：`FROM` 里写的是**依赖节点名**，不加任何前缀。）
- **忘了软删除**。`data_table` / `data_task` 都有 `deleted` 字段，漏掉就多算。
- **`transform` 挂多个依赖**。它只接受一个；要合并多个结果请用 `sqlite`。
- **在方法论里查元数据**。表/字段发现走 `opendataworks-platform-tools` 的元数据工具，
  不走只读 SQL 通道。
- **口径变了但没升 `version`**。调用方无从察觉，这正是要消除的漂移。
