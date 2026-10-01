# Pi 运行时模型输出上限实施计划

配套设计：[`../design/2026-10-01-pi-model-output-ceiling-design.md`](../design/2026-10-01-pi-model-output-ceiling-design.md)

## Tasks

1. `dataagent-runtime-pi/src/protocol/frames.ts`：`model` 增加可选 `max_output_tokens`。
2. `dataagent-runtime-pi/src/providers/stream-fn-resolver.ts`：`resolveModel()` / `resolveRuntimeModel()`
   接收该值，未配置时使用 `DEFAULT_MAX_OUTPUT_TOKENS`。
3. `dataagent-runtime-pi/src/kernel/cell.ts`：把该值传给模型工厂；最后一条 assistant 消息
   `stopReason === "length"` 时以 `PI_OUTPUT_TRUNCATED` 失败。
4. `dataagent-backend/core/skill_admin_service.py`：`resolve_runtime_provider_selection()` 返回所选模型的
   `max_output_tokens`。
5. `dataagent-backend/core/pi_runtime.py`、`core/task_executor.py`：`PiRunContext` 携带该值，仅在配置时写入 init 帧。
6. 测试：runtime 覆盖上限透传、默认值、截断判失败；后端覆盖 resolver 与 init 帧。

## Verification

- Pi runtime：`npm test`。
- 后端：定向 pytest（`test_skill_admin_service.py`、`test_pi_runtime_dispatch.py`、`test_task_executor.py`），
  外加全量 pytest。
- 真实 smoke：为 DeepSeek 模型配置“最大输出 tokens”，通过 OntoFoundry 建模入口提交一次真实运行，
  确认运行成功并产出结果文件。

## Backout

回退这两处代码即可。无数据库变更；已配置的 `max_output_tokens` 会被旧代码忽略。

## Verification results

- Pi runtime：`npm test` 全部通过（151 tests），覆盖上限透传、默认值和截断判失败。
- 后端：`dataagent-backend/.venv-py313`，全量 pytest 779 通过。另有两项失败与本次改动无关，
  在改动前的代码上同样失败：
  - `test_every_builtin_skill_is_fully_tracked_by_git`：本机额外装了未跟踪的 `md2ossie` skill。
  - `test_real_cell_completes_the_protocol_round_trip`：本机 `.env` 配置了一个欠费的 provider，
    模型返回 403，而测试预期“无凭据”。
- 本地真实 smoke：Docker MySQL `127.0.0.1:3306`（session 库 `dataagent`），Docker Redis `127.0.0.1:6379`，
  DeepSeek `deepseek-v4-pro`（Anthropic 兼容接口），使用真实模型调用。
  - 最大输出 tokens 配置为 32000：经 OntoFoundry 建模入口提交真实运行，状态 `finished`，结果文件正常产出。
    该运行单轮输出最高 5393 tokens，未触及旧的 8192 上限。
  - 最大输出 tokens 临时配置为 512：请求一篇长文，运行以 `PI_OUTPUT_TRUNCATED` 失败，提示中的上限为 512。
    这说明配置值确实下发到了模型请求；之后已恢复为 32000，并删除测试 topic。
- 未覆盖：容器 sandbox 拓扑（`DATAAGENT_SANDBOX_MODE`）下的子容器路径。该路径在容器内调用同一个
  `core/pi_runtime.py`，但本次没有在容器拓扑下单独跑 smoke。
