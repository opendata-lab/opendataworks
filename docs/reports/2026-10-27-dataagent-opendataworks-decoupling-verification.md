# DataAgent 独立仓库迁移验证

日期：2026-10-27。依据 [设计](../design/2026-09-14-dataagent-opendataworks-decoupling-design.md)、[计划](../plans/2026-10-27-dataagent-opendataworks-decoupling-plan.md)。来源：opendataworks main `828eb2f8`。

## 实施结果

- `feat/import-dataagent`：先 `git rm -r` 删除原受控 MVP，再按 `git ls-files` 复制 DataAgent、contracts、评测与相应测试。只提交一次；原仓库历史保留。
- `feat/dataagent-split`：MCP 和五个平台 Skill 用 `git mv` 留在本仓库；迁出的 DataAgent、评测及对应测试用 `git rm` 删除。没有迁移虚拟环境、node_modules、dist、缓存、本地 topic 或未受控 Skill。
- DataAgent 不再 bootstrap 平台 MCP/智能体、不默认选择 MCP、不保留固定工具清单、URL/超时/授权特例、平台直连代理/CLI/数据源解析器或平台连接配置。HTTP/SSE MCP 的身份头由执行上下文统一填写，并覆盖配置中不同大小写的同名身份头。
- 两种引擎按 tools/list annotations 判断风险；缺失或非只读视为写工具，按会话权限模式门控，destructiveHint 进入高风险确认，plan 拒绝写操作。Pi 经既有持久化 permission 卡片机制确认，Claude SDK 每次运行发现 annotations，不用全局工具名缓存。
- Python 包、工具、服务、变量与 token 头统一为 odw 名称；所有 23 个 MCP 工具保留 annotations，建表标为高风险。普通 token header 由管理员配置，DataAgent 不解释其业务含义。
- 会话来源一步切为 `web`，widget 为 `DataAgentWidget` / `dataagent-widget.bundle.js` / `X-DataAgent-*`。主门户同步消费新名称。
- DataAgent 有独立 compose、配置、构建 workflow、SDK workflow、规则和 TODO。OpenDataWorks 只编排其原名镜像，移除 DataAgent 的平台凭据、MCP 依赖及源码挂载；独立镜像带通用 Skill，`dataagent-skills` 命名卷持久化 Skill，home-init 修正属主。部署指南列出普通 MCP、导入并启用 Skill、绑定智能体及主体账号映射步骤。
- 未改本仓库 Go 产品线 `opendataagent/` 和根 `skills/`；未做 Plugin、租户/API Key/白名单或旧名兼容；镜像随后改为 `oda-*`，npm 包改为 `@opendataagent/*`（见文末“改名”节）。

## 通用 Skill 逐项检查

| 候选 | 受控状态和结论 |
|---|---|
| chart-visualization | 受控；独立标准库脚本，不取数、不依赖平台/MCP；保留 |
| report-generation | 受控；处理已有行数据，依赖 pandas/openpyxl；保留 |
| entity-extraction / ontology-discovery / semantic-grounding | 属于 ontograph 项目（`ontograph/integrations/dataagent/skills/`），在本仓库只是未受控的本地副本，不是 DataAgent 内置 Skill；不迁移 |

`build_chart_spec.py` 已在 chart-visualization，`format_answer.py` / `generate_report.py` 已在 report-generation；无需重复合并。五个专属 Skill 留在 `odw-skills/`；直连脚本和对应测试删除，文档改为 MCP。

## 必要调整

1. 历史 Alembic 000008/000015 原本会在空库创建平台智能体。去掉这两个种子入口，保留版本链；默认助手种子描述通用化。仅增加末尾转换迁移会把空库种子变成普通平台记录，无法满足空库要求。
2. 新版本为 **`20261027_000031`**，down_revision **`20261027_000030`**。将既有 portal MCP source 改 configured，两个旧智能体及其草稿/发布快照改为非内置，保留 ID、内容及历史引用。topic source 改 web；widget 事件也有旧 source/default，迁移一并改写，避免事件继续产生旧名称。downgrade 恢复旧属性和来源。
3. 方法论 DAG 的 engine/run_methodology 通过已删除的直连脚本执行 SQL，不能继续保留。按设计删除这条依赖链及其测试，保留检索、绑定、工件和静态校验；Skill 指导模型按工件依赖调用 MCP 查询节点，没有增加替代执行器或兼容通道。
4. Pi 要求 Node >=22.19，独立仓库 `.nvmrc` 采用 22.22.0。新 Python 环境暴露原 requirements 缺少 pytest-asyncio，已补入，确保现有异步测试真正执行。
5. 同步迁移评测启动/离线脚本，清除本仓库构建、离线打包、启动脚本对已删除源码/CLI 的引用；部署契约测试改为检查独立边界。命名 Skill 卷取代源码绑定，避免独立镜像没有持久可写的导入目录。未验证真实镜像/卷/child-container 启动，见未覆盖项。

## 环境与命令

Python 3.13.4；在新位置重建 `dataagent-backend/.venv-py313` 并安装 requirements；该路径已被 gitignore 忽略。Node 22.22.0（DataAgent）和 20.19.0（门户），每轮 Node 命令前均成功 `nvm use`。

临时 Podman MySQL 8.0.45：`dataagent-split-verification-mysql`，`127.0.0.1:3316`。独立 schema：`dataagent_split`、`dataagent_upgrade`、`odw_split`、`odw_split_tests`、`da_split_business`。已有 MySQL 3306 的模型配置仅只读探测，没有改既有 opendataworks 库。复用 Redis `127.0.0.1:6379` 的 DB 13，不清空 Redis；只清理本次 task key，协调器正常释放自己的租约。

```sh
# opendataagent，仓库根（pytest.ini 同时收集后端和根评测测试）
python3.13 -m venv dataagent-backend/.venv-py313
dataagent-backend/.venv-py313/bin/pip install -r dataagent-backend/requirements.txt
SKILLS_ROOT_DIR="$PWD/.claude/skills" dataagent-backend/.venv-py313/bin/pytest -q --disable-warnings
. "$HOME/.nvm/nvm.sh" && nvm use
npm --prefix dataagent-frontend test -- --maxWorkers=2 --minWorkers=1
npm --prefix dataagent-frontend run build
npm --prefix dataagent-frontend run build:widget
npm --prefix dataagent-runtime-pi test
npm --prefix dataagent-runtime-pi run build
# 在 dataagent-backend 下，用临时 schema 环境执行
.venv-py313/bin/alembic upgrade head
.venv-py313/bin/uvicorn main:app --host 127.0.0.1 --port 18900

# opendataworks，使用新仓库 venv 的 pytest
../opendataagent/dataagent-backend/.venv-py313/bin/pytest -q odw-mcp-servers/opendataworks/tests
../opendataagent/dataagent-backend/.venv-py313/bin/pytest -q odw-skills
../opendataagent/dataagent-backend/.venv-py313/bin/pytest -q tests/test_deploy_config_contract.py tests/test_offline_package_env_policy.py
. "$HOME/.nvm/nvm.sh" && nvm use
npm --prefix frontend test
npm --prefix frontend run build
mvn -s /private/tmp/claude-502/-Users-guoruping-project-bigdata-opendataworks/8153a01d-5553-4bdf-ac77-51952e012d96/scratchpad/m2-settings.xml -o -q -pl backend -am test
```

Maven 测试通过 JAVA_TOOL_OPTIONS 固定 Spring 数据源为 `127.0.0.1:3316/odw_split_tests` 和临时账号，避免访问默认 3306 平台库；该库实际从零运行 54 个 Flyway 迁移。运行联调 Java 的 jar 另外以 JDK 8u361、`package -Dmaven.test.skip=true` 构建；Java 代码没有改动。

两边 Compose 使用本机 `docker-compose v2.37.0 config`（Docker CLI 未安装；有 Podman 和 Compose CLI），用模板临时生成 .env，分别校验 dev/prod 和独立 compose 后删除 .env。

## 测试结果

| 验证 | 结果 |
|---|---|
| DataAgent 全部 pytest（含评测） | **877 passed**，9 warnings，无失败/跳过 |
| DataAgent 前端 | **748 passed / 10 failed / 758**；66 文件通过、1 失败 |
| DataAgent build / build:widget | 均成功；bundle 产物新名称正确 |
| 共享投影契约最终复核 | 22/22 通过 |
| Pi 全量 | **183 passed / 1 failed / 184**；build 成功 |
| odw-mcp-server | **41/41** 通过 |
| odw-skills | **87/87** 通过 |
| 部署/真实离线包契约 | **13/13** 通过 |
| 门户前端 | **419/419**，59 文件；build 成功 |
| Java backend | **580，570 passed、7 failed、0 errors、3 skipped** |
| odw-auth / backend-agent-api | **31/31 / 3/3** 通过 |
| Java reactor 合计 | **614，604 passed、7 failed、0 errors、3 skipped** |
| Compose / git diff --check | 通过 |

前端 10 项仍全部是 `ToolOutputRenderer.spec.js` 的既有折叠/结果展示断言，来源仓库 TODO 和此前身份报告已确认，不扩展修复。本次无新增前端失败。

Pi 唯一最终失败是 `cell.init Bash timeout 2s reaches the real command`：本机 shell 运行超过 2 秒。既有 TODO 已记该时序问题；另用来源 HEAD 的未修改 Pi 源码临时副本、相同依赖和 Node 单独运行，仍为 1/1 失败；新仓库单独运行也同样失败。一轮并发执行还触发 channel_flush 的负向控制断言（无 flush 时意外收到全部帧），最终全量该项通过；已记录待办，不修改无关运行逻辑。

Java 7 个失败全部属于既有 `WorkflowRuntimeSyncRealIntegrationTest`，DolphinScheduler `http://localhost:12345/dolphinscheduler` 不可达：

- forwardReverseSyncAndDiffShouldWork
- importPreviewCommitAndExportShouldWorkWithRealDolphinExport
- publishPreviewShouldOnlyShowChangedTasksForExistingRuntimeWorkflow
- relationMismatchShouldRequireDecisionAndConvergeAfterManualSelection
- reversePreviewShouldFailForUnsupportedNodeType
- reversePreviewShouldPassForUpdateHeadWithoutInput
- runtimeEvolutionShouldRefreshTasksLineageAndDataResult

跳过：RealDolphinSchedulerIntegrationTest 的两个环境用例，以及未设置 ODW_POSTGRES_TEST_PORT 的 PostgreSQL 测试类。本轮未重跑 PostgreSQL 环境；因类级跳过，计数比此前启用 PostgreSQL 的报告少 8 项，不是删除测试。

## 空库、迁移与真实联调

- 空库运行 Alembic 至 head：只有 `agent_default`，无 MCP 记录；只有 chart/report 两个内置 Skill。uvicorn 在没有平台 MCP/backend 和平台变量时启动。真实登录 `local:split-smoke`，HTTP 建 topic、提交 `execution_mode=auto` 的 `你好，请直接回复 smoke-ok。`，waiting → finished，最终助手消息和 Pi 事件持久化成功。
- 旧数据库在 000030 插入 plugin 来源 portal MCP、两旧内置智能体、草稿/发布快照及 source=portal 历史 topic。升级验证普通可编辑记录、内容逐字段保留、is_builtin=false、历史会话可读；downgrade/再次 upgrade 成功；source/default 为 web，widget 事件 default 同为 web。
- 先探测已有 deepseek provider：真实 `deepseek-v4-pro` 返回 smoke-ok，模型可用。
- Java `18080`（含 backend-agent-api）、MCP `18801`、DataAgent `18900`。按部署步骤通过 HTTP 新增普通 MCP、导入并启用平台 Skill、创建并发布智能体；新增真实 MySQL 数据源并同步元数据。
- MCP initialize/tools/list 发现 23 个 odw 工具。未配置主体账号映射时，真实 `odw_query_readonly` 被后端拒绝：`未配置智能体的数据源账号映射`。
- 经平台 HTTP 配置人的 `split_person` 与智能体 `split_reader` 账号，均只授 SELECT。模型经 DataAgent → MCP → Java → MySQL 查询 `da_split_business.orders`，实际返回 `(1,17)` / `(2,25)`，合计 **42**，任务 finished。
- 后端查询历史：executed_by=2（本地用户）、当前 agent_id、check_account=split_person、execution_account=split_reader、preview_row_count=2。DataAgent topic 为 source=web、auth_user_id=local:split-smoke，16 条 Pi SDK record，engine_kind=pi_agent_core。身份头确实到达权限执行层。
- 通用身份单测还覆盖任意 HTTP/SSE MCP、保留普通认证 header、大小写无关地覆盖伪造身份 header、保持管理员配置 URL 原样。MCP 测试覆盖无前门 token 和业务调用缺身份时拒绝。

## 静态检查、未覆盖和清理

DataAgent 核心 Python、Pi、前端、prompts、通用 Skill 中无 OpenDataWorks/portal/ODW 常量或专属工具名。保留的旧值仅用于历史 Alembic、迁移回归夹具及迁移/历史说明；镜像及 npm 包的既有发布名称按范围保留。本仓库无受控 dataagent 源码；旧 MCP 包/服务/工具/配置名和旧源码引用已从运行代码、测试、部署脚本、当前 handbook/website 删除。历史 design/plan/report/changelog 保留当时事实；`/dataagent/widget/` 等 URL 与 Go 产品线路径不属于被删除的源码目录。

未覆盖：实际 Docker 镜像构建与生产 child-container/Skill 卷初始化及升级、Claude SDK 引擎真实模型会话、真实第三方 OAuth、PostgreSQL/Doris 端到端。均未宣称生产拓扑完整验证，DataAgent 生产拓扑待办继续保留。新增/承接 TODO：Plugin 打包导入、未受控本地 Skill、现有 ToolOutputRenderer、Pi 时序/负向控制、Skill 卷部署复核；平台既有 DolphinScheduler 环境限制保留。

临时 Java/MCP/DataAgent 进程已停止，运行时导入的 Skill 已删除；临时 MySQL 容器及匿名卷、五个临时 schema 和临时数据库账号一并删除。清理了本次 Redis task key、topic 工作区、模型探测临时凭据文件和 Pi 基线副本；未清空已有 Redis，未停止或删除既有容器。Python venv 和本地 Node 依赖保留，均被忽略。

两仓库仅在指定 feature 分支本地提交，不 push、不合并 main。最终提交与文件计数见交付回复。

## 改名（2026-10-27 追加）

opendataagent 中剩余的 opendataworks 名称一次性改掉，不保留旧名：

- 镜像：`opendataworks-dataagent-frontend/backend/runner/evals-builtin/evals-deepeval/evals-opik` → `oda-frontend/backend/runner/evals-builtin/evals-deepeval/evals-opik`；opendataagent 内镜像与离线包变量改为 `ODA_*`。opendataworks 的 compose、`.env.example`、离线打包与加载脚本只改默认镜像，变量名 `OPENDATAWORKS_DATAAGENT_*_IMAGE` 与容器名不变。
- npm 包（均未发布）：`@opendataagent/agent-conversation`、`@opendataagent/dataagent-runtime-pi`、`dataagent-frontend`；lockfile 用 `npm install --package-lock-only` 重新生成。
- contracts `$id` 统一为 `https://opendataagent/contracts/...`；投影样例的 skill 名改为 `example-business-knowledge`。
- 有意保留：历史 Alembic 迁移及其测试中的 `agent_opendataworks`、Pi README 中已删除镜像的历史说明、`docs/` 中指向来源仓库的链接。

验证：opendataagent pytest 877 通过；前端 748 通过、既有 `ToolOutputRenderer` 10 项失败；`build`、`build:widget`、`test:sdk`、`build:sdk` 通过；Pi 183 通过、既有 Bash 时序用例 1 项失败，build 通过；两边 compose `config` 通过；opendataworks 部署契约测试 13 通过。

上线注意：`oda-*` 镜像在 Docker Hub 上还不存在。opendataworks prod compose 默认 `oda-*:1.5.0`，发布前需由 opendataagent 先构建并推送对应 tag。以 `@opendataagent` scope 发布 npm 包前需先在 npm 上拥有该 scope。

## 部署拆分（2026-10-27 追加）

用户确认 OpenDataWorks 的 Compose 不再包含 DataAgent，两边可以在不同机器上；同时删除 Go 版产品线和 Gitee 发布同步。

opendataworks：

- `docker-compose.dev/prod.yml` 删除 `dataagent-*` 服务、YAML anchor 与 `dataagent-skills` 卷；frontend 不再依赖 DataAgent，默认挂载宿主机 `deploy/docker/nginx/frontend.conf`。
- 宿主机 nginx 配置中 DataAgent 的四段转发改为注释示例（`<DataAgent 主机>` 占位）；镜像内置 `frontend/nginx.conf` 删除这些转发，未接入 DataAgent 时 nginx 不会因解析不到上游而启动失败。
- log-collector 改用 `docker:27.5.1-cli`，默认只收集 backend 与 odw-mcp-server；socket 变量改为 `DOCKER_SOCKET`。
- `.env.example`、MySQL 初始化脚本与说明、离线打包/加载脚本、`start.sh`、`dump-logs.sh`、多架构构建配置中的 DataAgent 部分删除；`deploy/docker/dataagent/` 与 `dataagent-frontend.conf` 删除（opendataagent 已有）。
- 删除 `opendataagent/`（Go 版）、根 `skills/`、`opendataagent-build.yml`、`docker-build.yml` 中的 Go 镜像链接、`docs/handbook/parallel-agent-platforms.md`、`scripts/release/render-gitee-release-body.sh` 与 `tests/test_gitee_release_sync.py`。
- `deploy/README.md` 改为只描述门户，新增“接入 DataAgent”；README、AGENTS、handbook、website 同步。

opendataagent：

- Compose 固定 `name: opendataagent`。两边目录都叫 `deploy`，原先在同一台机器上会被当成同一项目，DataAgent 的 mysql 会挂上门户的 `deploy_mysql-data` 卷。宿主机端口改为 mysql 3326、redis 6389。
- 新增 `deploy/README.md`：运行配置、运行时目录与沙箱、登录配置、被门户内嵌时的转发，以及从旧内置部署迁移 `dataagent` 库、Skill 卷和运行时目录的步骤。

验证：

- opendataworks 根测试 10 通过（删除 Gitee 测试后无失败）；dev/prod `compose config` 通过；主前端 419 通过、build 通过。
- `nginx:1.30.3-alpine` 下 `nginx -t`：宿主机配置（注释状态）、镜像内置配置、以及把示例填上地址并取消注释后的配置均通过。
- opendataagent `compose config` 解析出项目名 `opendataagent`、卷 `opendataagent_mysql-data`、端口 8900/8901/3326/6389。
- 未验证：迁移步骤未在真实旧部署上演练；跨机器的 widget 转发与 MCP 访问未实测；opendataagent 尚无启动、日志导出与离线包脚本（已记入其 TODO）。
