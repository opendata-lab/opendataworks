# DataAgent 通用展示能力抽离设计

**Date:** 2026-09-15
**Topic:** dataagent-generic-presentation-skills
**Goal:** 把图表契约、报告生成、结果摘要三项与平台无关的能力，从 `opendataworks-platform-tools` 中抽出为独立的 DataAgent 内置 Skill，使不需要访问 OpenDataWorks 数据的智能体也能出图和出报告。

> 本文是 `2026-09-14-dataagent-opendataworks-decoupling-design.md` §4.3 的实施切片，只覆盖通用展示能力的抽离。
> 租户身份、数据权限白名单、`odw-plugins/` 打包、MCP 改名等内容仍以那份总体设计为准，不在本文范围内。

## Current State

新建一个智能体后，若它只需要出图或出报告、不需要查平台数据，当前没有可用路径：

1. `build_chart_spec.py`、`generate_report.py`、`format_answer.py` 三个脚本物理上位于
   `dataagent/.claude/skills/opendataworks-platform-tools/scripts/`，与元数据、血缘、DDL、只读 SQL 等平台能力同目录。
   要获得出图能力，智能体必须整体启用 `opendataworks-platform-tools`，连带引入 portal MCP 依赖、`odw-cli` 和平台数据访问面。

2. 基础系统提示词 `prompts/data_agent_system_prompt.md:51-56` 硬编码了调用路径：

   ```
   "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/build_chart_spec.py" ...
   ```

   而 `DATAAGENT_PLATFORM_SKILL_ROOT` 只在解析得到 platform-tools 目录时才注入
   （`core/agent_runtime.py:643-648, 680-681`；子容器见 `sandbox_runner_main.py:414-415`）。
   未启用该 Skill 的智能体读到的是一个指向不存在路径的强制指令。

3. 这三个脚本对平台的实际依赖只有 `_opendataworks_runtime.py` 中的三个纯函数
   `print_json`、`error_payload`、`load_json_input`，不涉及 MCP、`odw-cli`、数据范围或平台环境变量。

4. `assets/chart-template/*.json` 共 9 个图表选型参考文件当前无任何代码或文档引用，处于孤儿状态。

## Problem

### 能力与平台耦合在同一个 Skill 边界内

出图和出报告是对「一份行数据」的加工，与数据从哪来无关。把它们和平台访问能力绑在一个 Skill 目录里，
使「只要出图」变成「必须拿到平台数据访问面」，违反最小权限，也让不接入 OpenDataWorks 的部署无法使用这些通用能力。

### 基础提示词持有某个具体 Skill 的私有路径

基础提示词属于所有智能体，`DATAAGENT_PLATFORM_SKILL_ROOT` 属于单个 Skill。
前者引用后者，导致提示词的正确性依赖于智能体的 Skill 选择，且无法在不改提示词的前提下更换图表实现。

### 缺少引用兄弟 Skill 的通用机制

现有环境变量都不适合作为跨 Skill 路径锚点：

- `DATAAGENT_SKILL_ROOT` 的值是 `discovery_root / primary_folder`（`core/skill_admin_service.py:1629-1630`），
  即**主 Skill 自身目录**，随智能体配置变化，不能用于定位兄弟 Skill；
- `DATAAGENT_ENABLED_SKILL_ROOTS` 是 JSON map，适合脚本读取，不适合写进 SKILL.md 供模型直接展开；
- `SKILLS_ROOT_DIR` 语义正确（已被 `skill_discovery.py` 校验为 `.claude/skills`），
  但不在 Pi runtime 的工具环境变量白名单内，脚本读不到。

因此每新增一个需要被其它 Skill 或提示词引用的 Skill，就要新增一个 `DATAAGENT_<X>_SKILL_ROOT`，
并同步修改 Pi 白名单。这正是 `DATAAGENT_PLATFORM_SKILL_ROOT` 的由来，不应继续复制。

## Scope

覆盖：

- 新增内置 Skill `chart-visualization` 与 `report-generation`；
- 三个脚本及图表模板资产从 `opendataworks-platform-tools` 迁出，原位置不保留副本；
- 复用既有的 `SKILLS_ROOT_DIR` 作为跨 Skill 路径锚点，并把它加入 Pi runtime 白名单；
- 基础提示词移除具体命令模板，命令契约下沉到新 Skill 的 SKILL.md；
- 内置 Skill 注册表与内置智能体 Skill 绑定同步更新。

不覆盖：

- 删除 `DATAAGENT_PLATFORM_SKILL_ROOT`：`run_sql.py` 等平台脚本仍在使用，属于总体解耦设计的后续阶段；
- 新增 `python-analysis` Skill：DataAgent 侧尚无对应脚本，需另行设计；
- `sandbox_runner_main.py:391-395` 的 `SKILLS_REQUIRING_PLATFORM_TOOLS` 语义调整：
  methodology-dag 对 platform-tools 的真实依赖是 `run_sql.py`，本次不变。

## Design

### 1. Skill 边界

| 新 Skill | 承接内容 | 依赖 |
|---|---|---|
| `chart-visualization` | `build_chart_spec.py`、`assets/chart-template/*.json` | 仅标准库 |
| `report-generation` | `generate_report.py`、`format_answer.py` | `pandas`、`openpyxl`（已在 requirements 内） |

两个 Skill 各自携带一份 `scripts/_skill_io.py`，内容为从 `_opendataworks_runtime.py` 抽出的
`print_json`、`error_payload`、`load_json_input` 三个纯函数。

不抽公共库、接受这一份重复，理由是 Skill 必须自包含：沙箱按 Skill 目录逐个挂载，
跨目录共享模块会引入新的路径依赖，代价高于复制约 40 行无状态代码。
`opendataworks-platform-tools` 保留自己的 `_opendataworks_runtime.py` 不变。

### 2. 跨 Skill 路径锚点

复用既有的 `SKILLS_ROOT_DIR`，不新增变量。它已经被 `core/skill_discovery.py` 校验为
`.claude/skills` 目录，并在每套 Compose 中以容器内绝对路径 `/app/.claude/skills` 定义，
语义正是所需的锚点。

**不能新增 `DATAAGENT_SKILLS_DIR`**：该名称已被部署层占用，
在 `deploy/.env.example` 与 Compose 的 volumes 中表示**宿主机源路径**
（如 `../dataagent/.claude/skills`）。DataAgent 服务使用 `env_file: ./.env`，
整份 .env 会注入容器，因此容器内已存在一个同名但值为宿主路径的变量。
复用该名称会造成同名两义，且容器内取到的路径根本不存在。

取值来源：

- 宿主进程：`core/agent_runtime.py` 从已解析的 skill 根推导后**显式写入**。
  不直接调用 `resolve_skill_discovery_root_dir()`——它在 `SKILLS_ROOT_DIR` 未配置时抛错，
  而该 env 构建路径在配置缺失时原本仍可正常完成；
  也不能依赖 `os.environ` 透传，因为该值由 pydantic Settings 解析，来自 `.env` 时不回写 `os.environ`。
- 子容器：`sandbox_runner_main.py` 已无条件注入 `CHILD_SKILLS_ROOT`，无需改动。

任意 Skill 引用兄弟 Skill 都用同一形式：

```bash
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/<skill-folder>/scripts/<name>.py" ...
```

该变量必须加入 `dataagent-runtime-pi/src/tools/tool-registry.ts` 的环境变量白名单。
生产默认引擎是 `pi_agent_core`，不在白名单内的变量不会传给 Bash 工具，
遗漏会表现为本地 `claude_code` 引擎正常、生产静默失效。

### 3. 提示词职责划分

基础系统提示词保留与实现无关的通用硬规则：

- 图表只能由脚本实际执行产出的 `chart_spec` JSON 表达；
- 禁止凭记忆手写 `chart_spec` 契约或 `<chart_spec>` 标签；
- 禁止用 Markdown 图片语法、图片链接或伪 URL 模拟图表。

具体命令模板、参数列表、图表类型枚举和 `chart_spec` 字段契约移入 `chart-visualization/SKILL.md`。
这与仓库既有约定一致：Skill bundle 是调用契约的唯一真相源。

保留防幻觉硬规则而非一并下沉，是因为这些规则约束的是「回答里不许出现什么」，
对未启用图表 Skill 的智能体同样适用，属于通用输出质量要求。

### 4. 注册与绑定

- `core/skill_admin_service.py:42` `BUILTIN_SKILL_FOLDERS` 加入两个新 folder，
  使其 `source` 判定为 `bundled` 并受删除/改名保护（`:1481, :1541`）。
- `DEFAULT_ENABLED_BUILTIN_SKILL_FOLDERS`（`:47`）加入两者，使全新部署默认可用。
- `core/agent_profile_service.py` 中 `agent_opendataworks` 的 `skill_folders` 追加两个新 folder，
  补回从 platform-tools 迁走的能力；`agent_default` 同样追加，使默认智能体开箱具备出图与出报告能力。

用户自建智能体的能力恢复不依赖上述默认值：两个 Skill 成为独立可选项后，
在 Agent 配置页可以单独勾选，无需再连带启用 `opendataworks-platform-tools`。

## Interfaces

### 环境变量

| 变量 | 值 | 注入位置 | 条件 |
|---|---|---|---|
| `SKILLS_ROOT_DIR` | skills 发现根目录绝对路径 | `core/agent_runtime.py` 新增显式写入；`sandbox_runner_main.py` 已有 | 无条件 |

### 脚本调用契约

脚本的 CLI 参数、stdin/stdout JSON 契约和输出 `kind` 字段全部保持不变，
仅改变文件所在目录与提示词中的路径写法。因此前端 `chart_spec` 渲染链路无需改动。

### 移动清单

```
opendataworks-platform-tools/scripts/build_chart_spec.py  → chart-visualization/scripts/build_chart_spec.py
opendataworks-platform-tools/assets/chart-template/*.json → chart-visualization/assets/chart-template/*.json
opendataworks-platform-tools/scripts/generate_report.py   → report-generation/scripts/generate_report.py
opendataworks-platform-tools/scripts/format_answer.py     → report-generation/scripts/format_answer.py
```

## Tradeoffs

**采用：一次性移走，不保留兼容副本。** 与仓库「不保留多份等价路径」的规则一致。
代价是升级窗口内已选中 platform-tools 的存量智能体会短暂失去出图能力，
通过同步更新内置 profile 绑定消除。

**未采用：每个新 Skill 配专属环境变量。** 会把 `DATAAGENT_PLATFORM_SKILL_ROOT` 的模式复制两遍，
且每加一个 Skill 都要改 Pi 白名单。

**未采用：新增 `DATAAGENT_SKILLS_DIR`。** 与部署层既有的宿主路径变量同名两义，见 §2。

**未采用：命令模板留在基础提示词、仅替换路径变量。** 改动更小，但基础提示词继续持有
某个具体 Skill 的私有调用细节，未解决职责错位，后续换实现仍要改提示词。

**未采用：抽公共 Skill 运行时库。** 见 §1，与沙箱按目录挂载的模型冲突。

**风险：命令模板下沉后模型可能不再稳定调用脚本。** 基础提示词保留了「禁止手写契约」硬规则，
但强制调用的指令强度下降。需通过端到端 smoke 验证图表实际渲染，而非仅靠单测。

## Verification

1. 脚本级：新路径下 `build_chart_spec.py` / `generate_report.py` / `format_answer.py` 的既有单测全部通过。
2. 独立性：在不启用 `opendataworks-platform-tools`、未设置 `DATAAGENT_PLATFORM_SKILL_ROOT` 的环境下，
   两个新 Skill 的脚本可独立执行并产出合法契约。
3. 运行时：`SKILLS_ROOT_DIR` 在宿主进程与子容器两条路径下均被注入且取值正确。
4. Pi runtime：白名单包含新变量，且 `npm run build` 后编译产物生效。
5. 端到端：新建一个只启用 `chart-visualization` 的智能体，提交一次不需要查库的出图请求，
   确认脚本被实际调用、`chart_spec` 契约产出且前端正常渲染。
