# 沙箱输入限制与报错验证

设计：[沙箱复用](../design/2026-06-08-dataagent-sandbox-warm-container-reuse-design.md)。
计划：[执行计划](../plans/2026-06-08-dataagent-sandbox-warm-container-reuse-plan.md)。

## 修复与行为

- 子容器 stdin 单行缓冲使用 `max(1 MiB, AGENT_MAX_BUFFER_SIZE_BYTES)`，默认 10 MiB。
- Runner 按 UTF-8 JSON 字节数检查任务输入。超限返回 `sandbox_input_too_large`，说明实际大小、允许上限和处理方法，不发送超限数据，并保留子容器供后续追问。
- 子容器自身读取超限也返回结构化错误后退出；Runner 不再复用该子容器。
- 未返回结果的子容器保留 `sandbox_container_no_result`，消息展示退出码、任务 ID 和日志排查提示；错误详情包含容器名。原始 stderr 保留在日志，不直接展示到消息中。
- 主动取消与 Runner 停止保留原有 suspended 状态和不同错误代码。

## 定向测试

使用 `dataagent/dataagent-backend/.venv-py313/bin/python`（Python 3.13.4）：

```text
python -m pytest tests/test_sandbox_task_main.py tests/test_sandbox_runner_main.py tests/test_pi_runtime_frame_limit.py -q
42 passed
```

真实 OS stdin 管道覆盖 256 KiB 输入、配置为 12 MiB 时的 11 MiB 输入、超限错误和后续追问；Runner 测试覆盖中文 UTF-8 字节计数、异常退出、损坏子容器移除及两类取消。

## 本地 HTTP 传输冒烟

临时启动独立 Uvicorn Runner，通过真实 HTTP 请求调用 `/internal/sandbox/runs`，由 Runner 使用本机 Python 子进程运行实际 `sandbox_task_main._serve_loop` 和结果输出协议。仅模型执行使用桩，容器启动/停止使用本机子进程替代。

通过的场景：256 KiB 任务输入成功；中文输入超过 1 MiB 返回大小与上限；超限后追问成功；子进程以 7 退出时返回明确退出码及任务 ID；崩溃后替换子进程继续成功。临时 Runner 和子进程已停止。

本次未使用 MySQL、Redis、真实提供商凭据或真实模型，也未验证公开 NL2SQL 任务协调、状态及消息持久化。Podman 可用，但本机没有现成的 runner 镜像，未执行实际子容器拓扑冒烟；HTTP 冒烟验证的是 Runner 与子进程之间的任务传输及错误协议。

## 部署

重建并更新 runner 镜像（也用作 `DATAAGENT_SANDBOX_IMAGE`），重新创建已有预热子容器，才能加载修复。仅更新 backend 服务或环境变量不足以修复旧子容器源码。需要回退时使用上一版镜像，或通过现有 `DATAAGENT_SANDBOX_REUSE_ENABLED=false` 禁用复用。
