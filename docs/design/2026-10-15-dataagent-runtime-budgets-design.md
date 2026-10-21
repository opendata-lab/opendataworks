# DataAgent 运行预算与默认配置

## 现状与问题

助手已支持 `max_turns=0` 继承全局默认，但 Pi 的工具次数固定为 50、Bash
固定为 120 秒。SQL 预算可达 900 秒，仍可能先被 Bash 杀死。全局同时存在
旧的通用轮次/超时、模式专用预算和未接入 Pi 的空闲超时，容易改错参数。
外置 `DATAAGENT_SETTINGS` 在主后端生效，沙箱子进程不能依赖自己的环境重算预算。

## 范围与归属

涉及 DataAgent Python 后端、Pi TypeScript runtime、助手配置 Vue 文案及部署文档。
不改 Java 后端、数据库 schema、助手发布协议，不增加每个助手的基础设施配置。

| 所有者 | 配置 | 语义 |
| --- | --- | --- |
| 助手 | `max_turns` | 0 继承全局模式默认；1–200 明确覆盖，内置助手保留 0 |
| 助手 | prompt、tools、skills、MCP、data_scope | 保留现有能力和权限配置 |
| 模型 | provider/model、`max_output_tokens` | 保留模型能力配置；输出长度不属于助手执行预算 |
| 全局 | interactive/background max_turns、max_tool_calls | 按执行模式选默认预算 |
| 全局 | interactive/background total timeout、Bash timeout | 整体时长与单命令时长 |
| 全局 | `dataagent_run_idle_timeout_seconds` | Pi 无运行时事件的超时，工具心跳也属于活动 |
| 全局 | SQL 超时、并发、上下文裁剪、缓存、沙箱 | 运维资源和运行策略，保留现有归属 |

优先使用内置默认，只配置实际偏离默认的项目。全局显式覆盖使用现有环境变量或
外置配置 `DATAAGENT_SETTINGS`，不要在模板中重复填写默认值。助手仅在确定存在
特殊需求时填写正数 `max_turns`。优先级为助手明确覆盖（仅轮数） > 全局有效值 >
代码默认；执行模式 `auto` 与 `background` 统一走后台档。

## 默认值调优

| 预算 | 交互 | auto / 后台 | 处理 |
| --- | ---: | ---: | --- |
| 模型轮数 | 24 | 40 | 保留；增加次数前先看重复调用 |
| 工具调用次数 | 100 | 200 | 替换固定 50；仍有限制 |
| 总运行秒数 | 360 | 1800 | 保留，不继续放大总预算 |
| Bash 单命令秒数 | 300 | 900 | 覆盖同档 SQL 读取预算，受总运行预算约束 |
| 无活动秒数 | 300 | 300 | 保留 Pi 已生效的默认 |
| SQL 读取 / 写入秒数 | 300 / 60 | 900 / 60 | 保留 |

上下文 64000 tokens、90%→70% 裁剪、最近 6 轮保护、16 KiB 工具结果折叠、
并发 8、模型未配置时输出 8192 tokens 均保持原值；无证据时不统一放大资源。

## 配置契约与执行路径

1. `config.py` 是运行预算默认值与模式选择的唯一来源，对预算要求正数。
2. 删除未生效的 `agent_*_idle_timeout_seconds`、旧 `agent_timeout_seconds` /
   `agent_max_turns` 和重复的 `dataagent_run_total_timeout_seconds`。
   不增加兼容回退链；升级须把显式旧配置迁移到模式字段。
3. 控制面在分发任务前解析 `runtime_limits`，随内部 `TaskExecutionInput` 传给
   沙箱。子容器直接使用解析值，不重新决定全局默认。保留提交时已确定的总超时。
4. Pi 的 `cell.init.limits` 增加 `bash_timeout_seconds`；所有预算明确传入，
   Python `PiRunContext` 不再藏另一套默认。Bash 使用此预算。
5. `max_turns` 的助手覆盖和同一份预算也供 Claude SDK 使用；工具次数和 Bash
   预算的本次接入限 Pi，不宣称 Claude SDK 已实现相同限制。
6. 启动/运行日志输出有效预算，不含凭据。助手 UI 明确“每次任务模型轮数，0
   继承全局”，避免将其误读为整个会话长度。
7. 检查发现 Compose 和 `.env.example` 仍默认 `claude_code`，与 Python 默认及
   当前生产引擎不一致。统一为 `pi_agent_core`，显式选择 Claude 的部署继续尊重
   该选择；不把 Pi 专属限制描述成跨引擎已经生效。

## 超时链与恢复

前端使用任务提交和 SSE 订阅，`execution_mode=auto`。Nginx 当前 420 秒为相邻
读写操作的超时，不是总 SSE 时长，流中有 ping；仍按仓库约定把相关代理超时
设为 1860 秒以覆盖默认 1800 秒预算。前端流不依赖 axios 请求总超时。
超限任务保持现有失败语义，配置变更不复活已结束任务；重试是新任务，写操作
需要检查已完成结果。本次不引入自动续跑、自动重试或新的阶段状态。
提示将“单轮工具调用上限”改成“本次任务工具调用上限”，与累计范围一致。

### 2026-10-21 提交前审查补充

实测 Bash `sleep 2 & wait` 在 50ms 超时后仍等待约 2 秒：只终止 shell，后台
子进程继续持有输出管道。Linux 容器/macOS 本地运行均采用 POSIX 进程组：Bash
建立独立进程组，超时或取消时杀掉整组，避免 SQL/脚本在任务停止后继续执行。
已取消的信号在 spawn 前拒绝执行；spawn 到监听器建立之间的取消也要处理。
补充真实后台子进程与取消测试，不要求模型生成 exec 包装命令。

全套组合测试还确认既有 SDK 用例会修改进程环境（包括 PATH），测试 fixture
需在每个用例后恢复环境，以免后续 Compose/打包测试静默跳过或找不到 Bash。

## 验证与取舍

针对默认继承、助手覆盖、模式、非法值、已提交总超时、主进程→沙箱→Pi 协议、
Bash 实际杀进程验证；做真实 HTTP + MySQL + Redis + Pi 模型 smoke，记录环境。
没有历史分布证明 100/200 是最终最优值，后续按实际超限与调用分布调整。
配置入口保持精简；不新增助手字段或数据库迁移。
