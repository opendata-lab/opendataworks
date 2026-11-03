# 采集账号一致性与局部导航整理验证

日期：2026-10-29。关联[设计](../design/2026-10-29-portal-information-architecture-design.md)、[实施计划](../plans/2026-10-29-portal-information-architecture-plan.md)、[使用说明](../handbook/features/metadata-collection.md)。

## 交付范围

- 顶部为数据开发、数据治理、智能问数、管理中心。Layout 用一份路由映射和 Element Plus 左菜单组织现有页面，智能问数无侧栏。旧工作流/任务/监控和血缘等主要路径保留。
- 数据集成只保留 DataX 任务表；连接配置只在管理中心维护。采集页挂在数据治理 → 数据地图，集中全源/库/表采集、全源比对、自动 Cron、运行历史与待删除表。
- 采集范围新增平台账号接口，复用 `withSystemAccount` 和现有适配器；个人目录接口不变。采集、比对、范围、配置和历史端点执行管理员权限检查。
- 连接更新不写采集配置列；采集配置接口只更新开关/Cron及首次启用触发基准。没有数据库迁移。
- SQL 与任务抽屉只打开采集深链的新标签，不直接同步；目标不可见时保留名称并阻止执行。单表 SUCCESS 还需刷新目录匹配返回的表 ID；PARTIAL、FAILED、结果未知和目录待核实分别呈现。
- 既有部署文件、后端不可见表错误修复及其测试保持原内容；原 SQL 提示回归转入独立采集逻辑测试。初始导航/采集实施未触及独立 DataAgent 仓库；追加真实问答发现的前端竞态修复见文末。

## 自动验证

前端每个命令前均成功执行仓库 `.nvmrc` 的 `nvm use`（Node 20.19.0）。

```sh
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
npm --prefix frontend test -- src/views/settings/__tests__/useMetadataCollection.spec.js src/router/__tests__/navigation.spec.js src/views/__tests__/Layout.navigation.spec.js src/router/__tests__/routerLazyViews.spec.js src/router/__tests__/routeWarmup.spec.js src/views/datastudio/__tests__/useTableActions.spec.js src/views/datastudio/__tests__/DataStudioRightPanel.smoke.spec.js src/views/datastudio/__tests__/useCatalogTree.spec.js src/components/__tests__/taskEditDrawerQueryTarget.spec.js
npm --prefix frontend run build
```

结果：主验证 9 个文件、74 项测试通过，0 失败。同步更新管理中心名称指引后，补跑 `npm --prefix frontend test -- src/utils/__tests__/dataagentWidgetLoader.spec.js src/views/datastudio/__tests__/useMetadataGeneration.spec.js`，另 2 个文件、17 项通过；合计 11 个文件、91 项通过。采集逻辑覆盖权限不可见、连接异常、迟到响应、成功/部分成功/失败、超时、目录缺失/刷新错误、独立保存配置及停用源。实际 Element Plus 菜单挂载测试覆盖顶部/左侧路由切换、query 高亮和问数无侧栏；已有目录和任务执行目标测试通过。

```sh
mvn -pl backend -am -Dmaven.compiler.useIncrementalCompilation=false -Dtest=MetadataCollectionAuthorizationTest,DorisClusterControllerTest,DorisClusterServiceTest,DorisConnectionServiceTest,DataTableMetadataSyncServiceTest,DorisMetadataSyncServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：6 个类，Tests run: 30，Failures: 0，Errors: 0，Skipped: 0。权限测试使用实际 `AuthorizationAspect` 代理，断言普通用户被拒、预览回调在系统账号上下文运行。服务测试检查配置更新列以及连接更新不覆盖采集配置。首次运行测试表元信息未开启下划线映射，字段名断言失败；修正测试初始化后重跑通过。补充历史端点权限断言后单独复跑该类，3 项通过。

前端定向 ESLint：0 errors；遗留属性顺序、console 等 warning 保留，未扩大为格式清理。构建通过，有现有 Sass API 弃用及大 chunk 提示。Impeccable detector 检查本次布局/采集/配置/集成视图，返回 `[]`。`git diff --check` 通过。

## 真实 MySQL / HTTP 验证

本地前端 3000，后端 8082，MySQL 3306，平台测试库 `odw_split_local_20261029`。后端用 Java 8 重新打包启动，并保留原数据库及认证环境配置，未切换到其他库。没有停止数据库或调度系统。收尾时原前端进程已退出，重新以 Node 20.19.0 启动开发服务并保持原 8082 后端代理。

资源准备和清理在测试脚本外部独立执行：临时 MySQL 数据源使用 `127.0.0.1:3306`，避免与既有 `localhost:3306` 的源唯一约束冲突；复用原个人 `root` 映射和平台 `opendataworks` 账号。只创建临时物理表和普通用户，不改变既有业务账号授权。实际执行 `python3 /tmp/odw-navigation-smoke.py`，17 项断言全部通过：

| 场景 | 实际结果 |
| --- | --- |
| 个人目录列 `dataagent` 的表 | 看到 `aq_chat_message` |
| 同源采集预览 | 账号为 `opendataworks`，不包含 `dataagent` |
| 直接调用接口采集该不可见表 | 返回 FAILED，无平台表 ID，历史持久化 FAILED |
| 采集配置保存后用旧连接 payload 提交旧 Cron/开关 | 采集配置保持不变，连接凭据不受采集配置保存影响 |
| 平台账号已有权限的库中创建临时两字段表 | 预览可见，采集 SUCCESS，刷新目录找到同一 ID，两字段与成功历史真实落库 |
| 源停用 | 预览和执行均拒绝 |
| 采集账号密码错误 | 预览失败，不返回成功空范围，也不使用个人账号兜底 |
| 临时普通用户真实 HTTP 请求 | 范围、采集配置、采集接口均返回 403 |
| 清理 | 临时源、映射、元数据/字段、历史、物理表、测试用户均清除 |

使用的是可丢弃测试资源；`dataagent` 原表未被导入，原源配置和账号权限保持原状态。验证未通过扩大平台账号权限来掩盖可见范围差异。

## 浏览器检查与边界

实际渲染检查了顶部四项、治理地图子菜单、采集目标不可见提示及禁用按钮；个人目录仍列出 dataagent。采集页“修改数据源”深链打开唯一配置页的目标源编辑弹窗，表单没有自动采集/Cron；数据集成渲染 DataX 表格，没有源配置 Tab/替代链接。问数页的模块侧栏数为 0。

在约 1280 CSS 像素宽度下检查采集布局，截图保留在本地 `output/playwright/metadata-collection-scope-20261029.png`（该输出目录不提交）。SQL/任务新标签目标由定向测试锁定，保留原编辑页；未重跑工作流发布、Dolphin 执行、MinIO 实际备份/恢复等未改业务。

初次验收时本机未选择智能问数助手，真实问答尚未验证；用户随后要求补齐，该缺口已在下述追加验证中解决，并移入[统一待办的已完成项](../TODO.md#已完成)。本机没有真实 Doris，MySQL 验证不代表 Doris 全能力验证；既有环境缺口仍保留在待办。

资产目录、独立元数据检索、完整模型设计、字段血缘与新任务/运行详情体系均不在本次实现范围。自动采集仍全源，手动范围不持久化；超时后只提供历史人工确认，没有新增异步作业或自动重试。

## 导航与采集页面样式补充

2026-10-29 按用户反馈将顶部四项相对完整顶栏居中，品牌在左、用户在右。窄容器改为流式布局，导航内部可横向滚动。真实浏览器测量菜单与顶栏中心差小于 0.01px，约 770px 宽度时无重叠或顶栏溢出；布局路由测试 1 项及前端构建通过。元数据采集页按用户要求改用白色内容背景。

## 现有数据与真实问答追加验证

2026-10-29 按用户“基于现有数据测一下、完善一下”的要求，在前述临时资源验证之后保留以下本地配置；此前“原配置与授权未变”仅描述临时冒烟阶段。

### 采集与查询身份

- 源 1 的实际 MySQL 连接已改为 `MYSQL` 类型；平台采集账号仍为 `opendataworks`，个人 `root` 映射保留。只为该平台账号补 `dataagent.aq_chat_message` / `dataagent.alembic_version` 两张表的 SELECT，无 root、写入或管理授权。
- 两表按表采集均为 SUCCESS：运行 33 → 表 1957、6 个字段；运行 34 → 表 1958、1 个字段。刷新平台目录均找到对应 ID；采集页当前库显示两张可见表，执行按钮可用。
- 已有 `xinghe_retail` 六张样例表补采成功（运行 35）：更新 6 张表、新增 30 个字段、errors 为空。未读取聊天消息明细。
- 智能体 `agent_opendataworks` 映射到专用 `odw_query_ro_local` 账号，仅 SELECT `xinghe_retail.*`，主机限定为本机 Podman 网关实际来源；随机凭据由账号映射持久保存。真实 MCP 查询样例库成功，查询 `dataagent` 被数据库权限拒绝。

### 本地助手接入

- 门户选择已有“OpenDataWorks平台助手”，复用已配置的 DeepSeek `deepseek-v4-pro` 和 Pi 运行时；未替换模型凭据或修改助手能力定义。
- 通过现有导入接口安装本仓库的 business-knowledge、data-dev、platform-tools 三个业务 Skill 到独立 DataAgent。Portal MCP 按已有 Token/header 契约接入，检测通过 23 个工具。
- 修复本地 widget 网站未登记导致的 403：新增 `opendataworks-portal`，仅允许 `http://127.0.0.1:3000` 和 `http://localhost:3000`，不允许匿名；保留既有网站和密钥。未使用通配来源或关闭鉴权来绕过。
- 环境：门户前端 3000、Java 8 后端 8082（`odw_split_local_20261029`），MySQL `127.0.0.1:3306`，Redis `127.0.0.1:6379`；独立 DataAgent Python 3.13 虚拟环境后端 8900、widget 3001、ODW MCP 专用虚拟环境 8801，新增服务均绑定回环地址。运行时与 widget 依照独立仓库 `.nvmrc` 使用 Node 22.22.0 构建。保留这些本地服务供用户继续使用，不停止既有 MySQL/Redis。

### 真实模型结果

通过门户“智能问数”实际输入：按城市统计 `xinghe_retail` 中状态为“已完成”的订单数与 `orders.pay_amount` 合计，金额降序并给出总计，仅展示聚合结果。

| 城市 | 已完成订单数 | 实付金额（元） |
| --- | ---: | ---: |
| 临江 | 7 | 351.80 |
| 青岚 | 5 | 317.10 |
| 总计 | 12 | 668.90 |

结果与独立只读 SQL 核对一致。任务 `task_368656a3b3044c33b0d22bcd` 实际完成（`finished`），SDK 记录确认 `engine_kind=pi_agent_core`，真实模型调用 6 轮、7 次工具，含 DDL 和 `odw_query_readonly`。终止事件、最终消息持久化及门户结果表格渲染均通过。截图 `output/playwright/intelligent-query-real-20261029.png` 留作本地证据，不提交输出目录。

### 本次发现的消息刷新竞态

首次提问时，初始化话题/历史请求迟到覆盖正在进行的消息，导致界面短暂返回欢迎页，完成后才恢复。独立 `opendataagent` 的 `useNl2SqlChat.js` 现利用既有运行版本和当前话题，忽略已过期的历史响应，并在忙碌期间只刷新话题列表。成功/失败的旧响应都不能清空新消息；不改变 API、事件协议或运行时。

定向回归覆盖初始化列表迟到、历史成功/失败迟到、切到新会话后的旧响应，连同原对话/Widget 用例共 47 项通过（2 个文件）。widget 重新构建通过（29.62s）。修复后的门户真实对话保留了用户消息，最终正常显示 `smoke-ok`；任务 `task_c32d4243fdb24b768e606e3f` 为 `finished`，用户/助手消息均持久化。测试结束后清理本轮两个测试话题，保留截图和以上任务标识用于记录。

白底采集页真实渲染背景为 `rgb(255, 255, 255)`，顶部保持居中。根前端构建通过；新截图 `output/playwright/metadata-collection-white-20261029.png`。

## DataWorks 采集界面参考与落地

2026-10-29 按用户追加要求参考[DataWorks 当前元数据采集官方说明](https://help.aliyun.com/zh/dataworks/user-guide/metadata-collection/)的列表进入详情、基本信息/数据范围/运行日志组织方式。公开文档用于核对功能与信息层次，没有登录阿里云租户控制台，也不声称像素级复刻当前控制台。

本项目保留一源一份采集配置：`/metadata-sync` 为源采集列表，可搜索名称/采集账号及按类型筛选；源名称/“配置采集”进入详情，“运行记录”直接进入历史页签。详情为基本信息（连接身份、触发计划）、数据范围（本次手动范围、比对、执行结果）、运行记录（原历史表格与明细）。连接仍只在管理中心编辑，原库/表深链默认进入数据范围，`tab=history` 可直达记录。白底、Element Plus 控件和顶部居中继续保留。

范围选择仍仅作用于本次手动采集，周期仍全源；未新增采集器实体、持久范围或后端接口。列表的启停列明确为“数据源状态”，不以源状态或调度基准时间冒充采集运行状态。运行状态/触发方式/范围以中文呈现，历史加载异常有明确错误与刷新入口；通过请求版本防止旧源的历史覆盖新源。页签切换保留未保存计划与库表目标，运行/保存/比对期间禁止切换到其他目标。

验证使用 Node 20.19.0，执行：

```sh
npm --prefix frontend test -- src/views/settings/__tests__/MetadataSync.navigation.spec.js src/views/settings/__tests__/useMetadataCollection.spec.js src/views/__tests__/Layout.navigation.spec.js
npm --prefix frontend run build
```

3 个文件 18 项通过，构建通过。新增组件用例覆盖列表搜索与返回、库表深链、切换页签不丢未保存 Cron、历史深链/详情、错误重试和旧源迟到响应。定向 ESLint 0 errors、5 项原有 warning；Impeccable 静态扫描未发现模式规则问题（Vue 文件为 regex 检查，不等于完整可访问性验收）。`git diff --check` 通过。

真实浏览器读取原源和历史，验证列表→基本信息→数据范围→运行记录/明细及返回；记录中 33/34/35 成功与此前失败均可查看。本轮不再次触发采集、不改数据库配置或授权，未重跑未改后端的 Java/模型全链路。约 1278px 桌面和 769px 窄视口下无页面横向溢出，基本信息按内容区宽度从两列降为一列；表格保留自身横向滚动。截图位于本地 `output/playwright/metadata-dataworks-list-20261029.png` 和 `metadata-dataworks-basic-20261029.png`，输出目录不提交。
