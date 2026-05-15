# 数据源路由

先结论：所有问数只允许单源路由。schema/database 来自用户明确输入、业务知识 Skill、metadata 或 datasource 工具；不要把 engine 当成 schema。

## 路由步骤

1. 如果用户或业务知识 Skill 已确认 database/schema/table，先校验这些对象是否真实存在。
2. 如果目标表不明确，使用 metadata 检索定位候选表和字段。
3. 如果 engine 不明确，使用 datasource 工具解析。
4. 如果候选表分布在不同 engine 或 database，要求用户缩小范围。
5. 数据库明确后，SQL 统一写 `<schema>.<table>`。

## 单源约束

- 不在一次 SQL 中拼接跨源联查。
- 不在 database 未确认时执行 SQL。
- 不把示例 SQL 的 engine 直接套到真实问题。
- 不把 engine 名写成 SQL schema。

## 必须先追问的路由冲突

- 用户问题同时命中多个数据库。
- 候选表分布在不同 engine。
- 同一指标在多张表中都有实现且口径不清。
- 业务知识 Skill 未定义用户所需的默认过滤或对象含义。

## 路由完成条件

- database/schema 已确认。
- engine 已确认。
- 表和必要字段已确认。
- SQL 可在单一数据源内完成。
