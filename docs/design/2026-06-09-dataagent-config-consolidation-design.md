# DataAgent Config Consolidation Design

## Current State

DataAgent deployment exposed several variables for the same physical roots:

- `DATAAGENT_HOME_HOST_DIR`, `DATAAGENT_SANDBOX_ROOT`, and
  `DATAAGENT_SANDBOX_HOST_ROOT` all described the persistent topic runtime root
  from different viewpoints.
- `DATAAGENT_SKILLS_DIR` and `DATAAGENT_SANDBOX_HOST_SKILLS_DIR` both described
  the same mounted skills source, with `scripts/start.sh` copying one value into
  the other.
- `DATAAGENT_SANDBOX_IMAGE` duplicated `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE`.
- Portal MCP frontdoor token settings were duplicated between portal-mcp server
  variables and DataAgent client variables.

This made `.env` look like it had more independent choices than the runtime
actually supports.

## Solution

Expose only deployment-owned inputs:

- `DATAAGENT_HOST_ROOT` is the host-side persistent DataAgent runtime root.
  Compose mounts it to the fixed container path `/dataagent_runtime`.
- `DATAAGENT_SKILLS_DIR` is the only external skills source. The sandbox runner
  discovers its host source by inspecting its own `/app/.claude/skills` mount and
  uses that source for child skill binds.
- `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE` is the single runner image setting.
  Compose injects the same value as internal `DATAAGENT_SANDBOX_IMAGE`.
- `PORTAL_MCP_TOKEN` and `PORTAL_MCP_TOKEN_HEADER_NAME` are the shared
  frontdoor/client token settings. Compose injects them into both portal-mcp and
  DataAgent.

The container-visible runtime root is intentionally fixed at
`/dataagent_runtime`; it is not an external `.env` knob. Topic layout remains:

```text
/dataagent_runtime/<topic_id>/
  workspace/  -> child /mnt/workspace
  home/       -> child /mnt/home
  logs/       -> runner-side task logs
```

## Interfaces

Removed external variables with no compatibility fallback:

- `DATAAGENT_HOME_HOST_DIR`
- `DATAAGENT_SANDBOX_ROOT`
- `DATAAGENT_SANDBOX_HOST_ROOT`
- `DATAAGENT_SANDBOX_HOST_SKILLS_DIR`
- `DATAAGENT_SANDBOX_IMAGE`
- `DATAAGENT_SANDBOX_RUNNER_URL`
- `DATAAGENT_SANDBOX_NETWORK`
- `DATAAGENT_PORTAL_MCP_TOKEN`
- `DATAAGENT_PORTAL_MCP_TOKEN_HEADER_NAME`

New or retained external variables:

- `DATAAGENT_HOST_ROOT`
- `DATAAGENT_SKILLS_DIR`
- `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE`
- `PORTAL_MCP_TOKEN`
- `PORTAL_MCP_TOKEN_HEADER_NAME`

Internal env still exists where needed by services, but it is set by compose and
is not documented as a user-facing `.env` setting.

## Tradeoffs

Removing compatibility fallback makes upgrades stricter: an old `.env` must be
updated before redeploying. The benefit is a single clear contract and no hidden
precedence rules between old and new names.

## 2026-10-16: 默认优先的部署配置精简

### 问题与范围

上一轮已统一路径和令牌；当前 `.env.example` 仍有约 70 个显式赋值，Compose
在 backend/runner 重复注入同一组 DataAgent 环境，又把代码默认的任务、调度、
沙箱池参数重复声明为部署旋钮。审批等待超时已经废弃，离线脚本仍生成未被读取
的 `DATAAGENT_LLM_JSON_FILE`。Spring 数据库账号也要求和 MYSQL_* 手工重复填写。

本轮修改根 deploy 的环境模板、两个 Compose、启动/离线打包脚本、相关文档和
回归测试。保留前一轮运行预算调整，不改助手/模型配置或数据库结构；不改独立的
`opendataagent` 部署。实际本地 `.env` 仅删除确认等价或没有消费者的项，并保存
权限为 0600 的临时备份，不打印凭据、不覆盖真实差异值。

### 配置归属

| 位置 | 保留内容 |
| --- | --- |
| `.env.example` 常规区 | MySQL / JWT / 服务令牌、DataAgent DB 凭据、宿主 runtime / skills 目录 |
| `.env.example` 可选注释 | 镜像定位、生产子网、HTTPS Cookie、UID/GID、容器引擎/socket、执行拓扑 |
| Compose | 服务名/容器路径/端口、内部 API/MCP 地址、共享令牌映射、角色特有接线 |
| `config.py` / 应用代码 | 任务/调度/缓存/池/预算等默认值；通过已有原生字段按需覆盖 |
| `DATAAGENT_SETTINGS` | backend 的运行策略覆盖，如轮数、工具次数、超时、并发 |
| 页面 registry | 模型供应商、助手、已导入的 MCP 服务；不再生成无消费者的 settings.json |

不增加新的配置文件或自动迁移层。`.env` 继续通过现有 `env_file` 传递给需要它的
backend/runner，因此原生 `TASK_*` / `SCHEDULE_*` / `DATAAGENT_SANDBOX_*` 高级覆盖
仍然可用，但模板不再把默认值逐项抄一遍。不再保留无意义的
`DATAAGENT_TASK_*` / `DATAAGENT_SCHEDULE_*` 前缀转换。

### Compose 收敛

1. 用一个 `x-dataagent-environment` YAML anchor 共用 DataAgent 的 DB、平台 API、
   MCP、技能根接线；backend 只补 Redis、外置配置和 runner URL，runner 只补宿主
   根、child 镜像/网络/身份。不把删除的默认字段藏回 anchor。
2. 删除镜像已定义的 Python/HOME/Node 路径和应用已有默认的 HOST/PORT、任务、
   调度、池、工具超时等环境映射。保留真正必须跨服务转换的令牌/DB 字段。
3. 静态前端不注入全量 `env_file`：移除主 frontend 的注入，dataagent-frontend
   保持原本无注入的状态。应用服务仍保留高级环境配置入口。
4. backend 的 Spring URL / username / password 默认由 MYSQL_* 推导，外部 MySQL
   是部署文档明确支持的场景，已有 SPRING_DATASOURCE_* 显式覆盖继续优先。
   DataAgent 的默认业务库同样使用 MYSQL_DATABASE，不再另设同义的数据库名输入。
5. MySQL healthcheck 改为读取容器真实 MYSQL_ROOT_PASSWORD，避免改密码后固定
   root123 导致健康状态异常。生产网络只保留实际使用的 prod subnet/gateway，
   dev 没有自定义网络，不再在模板宣称 dev subnet/gateway 生效。

### 模板与打包

只展开安装必需项，默认策略移回代码，长故障排查说明移到部署文档。保留离线
打包脚本消费的镜像示例；skills / sandbox 两个字段继续由离线包改写。删除
DATAAGENT_LLM_JSON_FILE 的改写/补写以及 settings.json 的复制，模型配置以 registry
为准。保留“不打包实际 .env”和“已有 .env 不覆盖”的已有约束。

### 兼容与验证

显式旧 DATAAGENT_TASK_* / DATAAGENT_SCHEDULE_* 改成 config.py 原生字段名或
DATAAGENT_SETTINGS；task_permission_wait_seconds 删除，不设兼容分支。
DATAAGENT_MYSQL_DATABASE 的旧别名删除，业务库跟随 MYSQL_DATABASE；会话库仍可用
DATAAGENT_SESSION_MYSQL_DATABASE 覆盖。高级原生环境变量及外部 Spring DB 配置保留。

验证两套真实 Compose 的展开结果：最小模板启动默认、现有本地值保持、非默认
凭据/镜像/外部数据库/沙箱/共享令牌、原生高级覆盖。执行真实离线归档测试（仅
容器镜像 CLI 使用 fake fixture），检查归档模板可解析且无旧 LLM 输入。执行
Shell 语法、相关 Python 回归和文档链接检查；不为配置审查重启用户已有服务。
