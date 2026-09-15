# DataAgent 通用展示能力抽离实施计划

**Date:** 2026-09-15
**Topic:** dataagent-generic-presentation-skills
**Design:** [2026-09-15-dataagent-generic-presentation-skills-design.md](../design/2026-09-15-dataagent-generic-presentation-skills-design.md)
**Branch:** `feat/dataagent-generic-report-chart-skills`（基于 `bceacc56`）

涉及栈：DataAgent Skill bundle、`dataagent-backend`（Python）、`dataagent-runtime-pi`（TypeScript）、基础提示词、`dataagent-frontend` 测试夹具。

## 任务

### T1 建立 `chart-visualization` Skill

新建 `dataagent/.claude/skills/chart-visualization/`：

- `scripts/_skill_io.py`：从 `opendataworks-platform-tools/scripts/_opendataworks_runtime.py`
  抽出 `print_json`（:107）、`error_payload`（:111）、`load_json_input`（:294）三个纯函数，不带入其余内容。
- `scripts/build_chart_spec.py`：`git mv` 迁入，仅把 import 从 `_opendataworks_runtime` 改为 `_skill_io`，逻辑不动。
- `assets/chart-template/*.json`：9 个文件 `git mv` 迁入。
- `SKILL.md`：新建。承接原基础提示词中的完整调用契约，并首次把 `chart-template` 挂进图表选型说明。
  正文不得出现 `OpenDataWorks`、`portal`、`odw`、`DATAAGENT_PLATFORM_SKILL_ROOT`。
  调用形式统一为：

  ```bash
  "$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/chart-visualization/scripts/build_chart_spec.py" ...
  ```

### T2 建立 `report-generation` Skill

新建 `dataagent/.claude/skills/report-generation/`：

- `scripts/_skill_io.py`：与 T1 同内容。
- `scripts/generate_report.py`、`scripts/format_answer.py`：`git mv` 迁入，同样只改 import。
- `SKILL.md`：新建，约束同 T1。

### T3 清理 `opendataworks-platform-tools`

- 确认三个脚本与 `assets/chart-template/` 已移出，原位置不留副本。
- `SKILL.md` 与 `reference/50-tool-output-contract.md`：删除图表契约、结果格式化、报表生成相关职责描述，
  改为指向两个新 Skill；「负责」清单去掉 `chart_spec` 与「SQL 执行结果格式化」。
- 核对 `_opendataworks_runtime.py` 中是否有仅被迁出脚本使用的函数，有则一并清理。

### T4 跨技能路径锚点 `SKILLS_ROOT_DIR`

复用既有变量，不新增。**不要叫 `DATAAGENT_SKILLS_DIR`**：该名称已被部署层占用为宿主机源路径，
且 `env_file: ./.env` 会把它注入容器，同名两义（见设计 §2）。

- `core/agent_runtime.py`：在 `runtime_env.update({...})` 块内显式写入 `SKILLS_ROOT_DIR`，
  取值用新增的 `_skills_dir_from(skills_root)` 推导，**不要**直接调 `resolve_skill_discovery_root_dir()`
  （配置缺失时会抛错），也不要依赖 `os.environ` 透传。
- `sandbox_runner_main.py`：已无条件注入 `SKILLS_ROOT_DIR=CHILD_SKILLS_ROOT`，无需改动。
- `dataagent-runtime-pi/src/tools/tool-registry.ts`：在「Skill paths and active folders」分组
  加入 `"SKILLS_ROOT_DIR"`。

### T5 改基础提示词

`prompts/data_agent_system_prompt.md:51-56`：

- 删除 `${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/build_chart_spec.py` 命令模板与参数枚举；
- 保留并改写为与实现无关的硬规则：图表只能来自实际执行图表技能脚本产出的 `chart_spec` JSON；
  禁止手写契约或 `<chart_spec>` 标签；禁止用图片语法、图片链接、`chart_spec://` 等伪 URL 模拟图表；
- 全文不得再出现 `build_chart_spec.py` 与 `DATAAGENT_PLATFORM_SKILL_ROOT`。

### T6 注册与绑定

`core/skill_admin_service.py`：

- `BUILTIN_SKILL_FOLDERS`（:42）加入 `chart-visualization`、`report-generation`；
- `DEFAULT_ENABLED_BUILTIN_SKILL_FOLDERS`（:47）加入两者。

`core/agent_profile_service.py`：

- `opendataworks_agent_payload()`（:300）的 `skill_folders` 追加两个新 folder；
- `default_agent_payload()`（:283）的 `skill_folders` 由 `[]` 改为两个新 folder，并同步更新 `description`。

### T7 同步关联文档

- `opendataworks-methodology-dag/SKILL.md:58`、`reference/30-invocation.md:58`：
  `build_chart_spec.py` 的归属从 platform-tools 改为 `chart-visualization`，并用新调用形式。
  该 Skill 对 platform-tools 的真实代码依赖只有 `run_sql.py`，`SKILLS_REQUIRING_PLATFORM_TOOLS` 保持不变。
- `opendataworks-business-knowledge/SKILL.md:13`、`opendataworks-data-dev/SKILL.md:28`：
  「图表生成」「结果可视化」的指向改为新 Skill。

### T8 测试

改：

| 文件 | 内容 |
|---|---|
| `tests/test_build_chart_spec_script.py:11` | `SCRIPT` 路径改到 `chart-visualization` |
| `tests/test_builtin_skill_content.py:58` | 断言改为新的提示词硬规则，去掉旧命令串 |
| `tests/test_builtin_skill_content.py:106,145` | platform-tools 必需 token 去掉 `chart_spec`；`DATAAGENT_PLATFORM_SKILL_ROOT` 因 `run_sql.py` 仍在而保留 |
| `tests/test_agent_runtime.py:51,114` | 补 `SKILLS_ROOT_DIR` 断言 |
| `tests/test_sandbox_runner_main.py:1199` | 补子容器 `SKILLS_ROOT_DIR` 断言 |
| `dataagent-runtime-pi/test/tool_env.test.ts:24,41` | 补新变量透传断言 |
| `dataagent-frontend/.../__tests__/chartSpec.spec.js:335` | 夹具命令串改为新形式 |

增：

- `tests/test_builtin_skill_content.py`：新增用例断言两个新 Skill 的 SKILL.md 不含
  `OpenDataWorks` / `portal` / `odw` / `DATAAGENT_PLATFORM_SKILL_ROOT`，且使用 `${SKILLS_ROOT_DIR}` 调用形式。
- `tests/test_report_generation_scripts.py`：新建。`generate_report.py` 与 `format_answer.py`
  当前**无任何测试覆盖**，迁移前需先补齐基线用例，否则无法判断迁移是否等价。

## 验证

按 AGENTS.md「先跑最小相关验证」执行：

1. `pytest tests/test_build_chart_spec_script.py tests/test_report_generation_scripts.py tests/test_builtin_skill_content.py tests/test_agent_runtime.py tests/test_sandbox_runner_main.py`
   —— 解释器用 `dataagent/dataagent-backend/.venv-py313`。
2. `dataagent-runtime-pi`：`npm test` 后 `npm run build`。后端 spawn 的是编译产物，不 build 则改动不生效。
3. 前端：`nvm use` 后跑 `chartSpec.spec.js` 与 `ToolOutputRenderer.spec.js`。
4. 独立性检查：在**不设置** `DATAAGENT_PLATFORM_SKILL_ROOT` 的 shell 里直接执行两个新 Skill 的脚本，
   确认产出合法契约。
5. 端到端 smoke（按 AGENTS.md 本地 smoke 方法）：
   - 本地 MySQL `127.0.0.1:3316`、Redis `127.0.0.1:6379`、`SESSION_MYSQL_DATABASE=dataagent`；
   - `DATAAGENT_RUNTIME_KIND` 保持默认 `pi_agent_core`——本次改了 Pi 白名单，**必须**在 Pi 下验证；
   - 提交任务时带 `execution_mode: "auto"`，避免落到 360s 交互档；
   - 新建一个只启用 `chart-visualization` 的智能体，提交一次不查库的出图请求
     （如「把这组数据画成柱状图：A=10,B=25,C=7」），确认脚本被真实调用、`chart_spec` 产出、前端渲染正常；
   - 事后删除 smoke 会话与 `topic_*/` 工作区。

未跑的层次必须在提交说明中写明。

## 灰度与回退

**灰度**：改动全部在 DataAgent 侧，无 schema 变更，无平台 API 变更。
`BUILTIN_SKILL_FOLDERS` 与 profile 绑定的更新对存量数据是增量的，不删除用户已有配置。

**风险点**：升级后存量智能体若只勾选了 `opendataworks-platform-tools`，
在管理员补勾新 Skill 前会失去出图能力。内置 `agent_opendataworks` 由 T6 自动覆盖，
用户自建智能体需要在发布说明中提示。

**回退**：单 commit 回滚即可，无数据迁移需要反向操作。
若仅 Pi 侧出问题，可临时把 `DATAAGENT_RUNTIME_KIND` 切到 `claude_code` 止血，再修白名单。

## 执行顺序

T8 的「增」→ T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 的「改」→ 验证。

先补 `generate_report.py` / `format_answer.py` 的基线测试再迁移，否则这两个脚本的迁移等价性无从验证。
