# portal-mcp 工具 Schema 内联 — 技术设计

- 日期：2026-09-10
- 关联计划：`docs/plans/2026-09-10-portal-mcp-schema-inlining-plan.md`
- 受影响栈：`dataagent/portal-mcp`（独立镜像）、经由 MCP 协议影响所有 DataAgent 运行时

## 1. 现状与根因

### 症状

一次真实问数（语义确认后需先取表 DDL、再执行只读聚合查询）连续两轮失败或退化：

| 时间 | 现象 |
|---|---|
| 09:50 | `portal_get_table_ddl` 连失败 4 次、`portal_search_tables`、`portal_query_readonly` 各失败一次；脚本 fallback 同时不可用；22 轮推理后**没有给出答案** |
| 11:26 | 同样的 MCP 工具仍失败，靠脚本 fallback 才答出结果 |

失败信息统一为：

```
Validation failed for tool "portal_get_table_ddl":
  - params: must be object
Received arguments: {"params": "{\"database\": \"public\", \"table_name\": \"...\"}"}
```

### 根因

`portal_mcp/app.py` 的 23 个工具统一采用 `async def tool(params: SomeInput)` 单参数包裹签名。FastMCP 由此生成的 `inputSchema` 形如：

```json
{
  "$defs": { "TableDdlInput": { "properties": { "database": …, "table": …, "table_id": … } } },
  "properties": { "params": { "$ref": "#/$defs/TableDdlInput" } },
  "required": ["params"]
}
```

字段定义完整，但位于 `$defs`，顶层只有一个 `$ref` 指针。

`pi-ai@0.85.1` 的 Anthropic 适配器在 **non-strict** 模式下重建 schema：

```js
// node_modules/@earendil-works/pi-ai/dist/api/anthropic-messages.js
const legacyInputSchema = { type: "object", properties: schema.properties, required: schema.required };
const inputSchema = strict === true ? { ...parameters, ...legacyInputSchema } : legacyInputSchema;
```

`$defs` 被丢弃，而 `properties.params` 里的 `$ref` 仍指向 `#/$defs/TableDdlInput` —— **模型收到一个悬空引用**，无法得知字段名，只能猜测（猜出 `table_name`，真实字段为 `table`），服务端 `extra="forbid"` 随即拒绝。

### 为什么既有措施没有解决

`ef625cb1` 在 pi 客户端加了 `prepareArguments`，能把模型传来的 JSON 字符串还原为对象。但它修的是**传输形态**，不是**猜测本身**：还原出来的对象字段名依旧是错的。这解释了 11:26 那轮为何仍然失败，也解释了 `portal_resolve_datasource` 为何能成功——它只有一个必填 `database`，猜中概率高。

## 2. 方案对比

| 方案 | 改动位置 | `extra="forbid"` | 跨字段校验 / alias | 公开契约 | 覆盖范围 |
|---|---|---|---|---|---|
| 摊平工具签名 | portal-mcp × 23 handler | ❌ 未知字段被静默丢弃（实测） | ❌ 需逐个重建模型 | ❌ 变更 | 需逐个改 |
| 客户端解引用 | pi runtime | ✅ | ✅ | ✅ | 仅 pi 运行时 |
| **服务端内联（采纳）** | portal-mcp，一处 | ✅ | ✅ | ✅ | **所有 MCP 客户端** |

选择服务端内联的决定性理由：发布带悬空风险的 schema 是 server 侧的责任；客户端方案只能救一个运行时，`claude_code` 路径仍然无解。

摊平签名被否决的实测依据：FastMCP 由普通函数签名生成的参数模型**不拒绝未知参数**，传入 `table_name` 会被静默丢弃，反而比现状更难排查。

## 3. 设计

### 3.1 内联时机与范围

在 `build_mcp_server()` 注册完所有工具后，一次性遍历 `mcp._tool_manager.list_tools()`，对 `parameters` 中含 `$defs` 的 schema 做递归内联，产出自包含 schema。

**关键性质**：`tool.parameters` 仅用于**发布**；工具调用走 `tool.fn_metadata.call_fn_with_arg_validation()` 与原 Pydantic 模型。两者是不同对象，因此内联不影响任何运行期校验。

### 3.2 刻意收窄的内联器

内联器只处理 Pydantic 实际产生的一种形状：**无 siblings 的本地 `$ref`，解析结果为对象 schema**。其余一律抛 `SchemaInlineError`。

不做通用 JSON Schema 内联，因为每种「安全降级」都会发布一个比模型实际约束更宽松的 schema：

- **`$ref` + siblings**：JSON Schema 2020-12 下两者是合取关系。字典合并会让 sibling 的 `minLength: 2` 覆盖目标的 `minLength: 5`，实际放宽了约束。
- **递归引用**：降级为 `{"type":"object"}` 会丢失全部子约束，对递归数组或标量更是直接改变允许类型。
- **布尔 schema**：`true` / `false` 是合法的 JSON Schema 目标，但不是本内联器要处理的形状。
- **不可解析引用**：保留 `$ref` 却仍删除 `$defs`，会产出本设计要消除的那种悬空引用。

### 3.3 失败处理：生产降级、CI 阻断

`_inline_tool_schema_refs` 在模块导入期执行（`app = create_app()`），因此**抛错等于整个服务起不来，23 个工具全部不可达**。为一个工具的 schema 未优化而让服务停摆，代价不成比例。

- **默认（生产）**：单个工具内联失败时记录 `logger.error`，保留 FastMCP 原始 schema —— 与本改动引入前完全一致，不产生新的退化，其余工具照常内联。
- **`strict=True`（测试 / CI）**：同一条件直接抛错，使回归在合入前被拦截。

## 4. 契约影响

- **对客户端**：`tools/list` 返回的 `inputSchema` 不再含 `$ref` / `$defs`，语义等价且自包含。所有 MCP 客户端受益，无需适配。
- **对调用方**：工具入参形状不变，仍为 `{"params": {...}}`。
- **实测体积**：23 个 schema 合计 25,376 B → 23,512 B（**−7.35%**），无膨胀风险（当前每个定义仅被引用一次）。

## 5. 已知风险

| 风险 | 缓解 |
|---|---|
| 依赖 `mcp._tool_manager`（私有成员） | 已改用其公开 `list_tools()`；测试断言「发布 schema 无 `$ref`」，升级破坏会立刻暴露。实测 `mcp 2.2.0` 该结构未变 |
| 未来模型形状不受支持 | 生产降级 + CI 阻断（见 3.3） |
| 同一定义被多处引用时重复展开 | 当前不存在；建议后续补体积预算回归测试 |

## 6. 与 MCP SDK 2.x 的关系

实测 `mcp 2.2.0`：`params: SomeModel` 签名**仍然**产生 `$defs` + `$ref`，升级不解决本问题；而 2.x 是破坏性大版本（`FastMCP` → `MCPServer`、`inputSchema` → `input_schema`、`mcp.server.fastmcp` 路径移除）。

结论：**升级对本问题零收益**。本内联方案在 2.x 上无需重写（`_tool_manager.list_tools()` 与 `.parameters` 结构一致），因此不构成后续升级的障碍。
