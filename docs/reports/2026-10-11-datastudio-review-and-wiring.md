# Data Studio 功能接通 Review 与后续修复

日期：2026-10-11。审查基线：提交 `f159053e` 与 Claude 独立工作树 `datastudio-function-wiring` 的未提交修改。已将 Claude 的 16 个已跟踪文件及 2 个新增测试合入主检出，再进行下列修复。Vue/Element Plus、Java、DataAgent 与部署边界保持不变；本轮仅改前端与文档，无 API 或 schema 迁移。

## Review 发现及修复

| 优先级 | 触发与影响 | 修复 |
| --- | --- | --- |
| P1 | A 表打开新鲜度删除确认后切 B，原实现确认后使用当前 `tableId`，会删除 B 配置 | 确认内容显示表名；确认前锁定目标与上下文，切表使操作失效 |
| P1 | 对象页签改变 SQL 执行连接后，元数据/字段保存、软删除、分区、智能元数据取值与采纳仍使用全局查询连接 | 共用 `objectSource.js`，优先对象 `sourceId/clusterId`，确认与写操作前锁定连接；DDL、访问统计及创建后的刷新也统一选择对象连接 |
| P2 | A 的新鲜度保存/检查完成会重载 B 的配置，保存还会退出 B 的编辑态；历史弹窗在切表后仍显示 | 所有读写后的 UI 更新使用上下文序号；字段/历史各自请求序号隔离 loading、错误与迟到结果；切表关闭历史，卸载使请求失效 |
| P2 | 分区请求失败被缓存为 `[]`；切回或重新打开后失败提示消失并误示无分区 | 失败不缓存为空的成功结果，再进入自动重试；成功的空列表仍缓存 |
| P2 | 调度数据源加载失败仍保留旧选项并提示可保存，切调度实例可能沿用旧选择；重复打开抽屉被旧请求预填 | 请求序号与打开序号隔离，目录失败/空态及重试就地展示；切实例清空选择；初始化期间禁用提交，编辑任务保留原数据源 |
| P2 | 正式页面停止查询显示上次 EXPLAIN 的 55ms 和旧时间；旧停止响应可能覆盖后续新查询 | 执行开始清空时间，停止记录本次耗时与本地显示时间；runId 隔离，停止过程中禁用再次执行与连接切换 |
| P2 | 任务名称状态图标 Loading/CircleClose/CircleCheck 未导入 | 补充显式导入；触及 radio 改为 value，消除弃用警告 |

Claude 修复的演示新鲜度只读、空分桶/副本数不自动变为 1、引擎直接编辑入口继续保留。已确认的布局、树字号、页签结构和配色未改变。

## 验证

环境：Node 20.19.0 / npm 10.8.2，每次前端命令均先成功 `nvm use`。主检出依赖，无额外安装。

```text
npm --prefix frontend test -- src/views/datastudio/__tests__ src/views/__tests__/Layout.spec.js src/demo src/components/__tests__
npm --prefix frontend test -- src/views/datastudio/__tests__/useQueryExecution.spec.js
npm --prefix frontend run build
npm --prefix frontend run build:demo -- --outDir <workspace-artifacts>/datastudio-review-site
```

- 全部 31 个相关测试文件、254 项通过，失败 0 / 错误 0 / 跳过 0。最后的停止时间格式修正再跑查询聚焦测试，12 项通过。
- 新鲜度 5 个延迟/确认/卸载回归在 Claude 原版上全部失败，修复后通过；对象连接差异也由失败回归确认。新增正常保存、检查、删除、分区失败重试、目录切实例/迟到、既有任务初始化的覆盖。
- 正式与演示构建通过；Sass legacy API、大包与动态/静态混合导入提示为既有构建警告。
- 触及文件 ESLint 0 错误、36 个警告；已有未使用 catch 变量、console 与属性顺序警告。`git diff --check` 通过。
- 日志：`/tmp/odw-studio-review-final-tests.log`、`/tmp/odw-studio-review-freshness-red.log`、`/tmp/odw-studio-review-query-tests.log`、`/tmp/odw-studio-review-build.log`、`/tmp/odw-studio-review-demo-build.log`、`/tmp/odw-studio-review-lint.log`。

## 真实服务与页面烟测

- MySQL：本地 Podman 容器 `data-portal-mysql`，127.0.0.1:3306，业务库 opendataworks。3316 未监听。Redis 容器在线，本路径未使用。未改变 schema、连接和业务数据。
- Java 8：复用 Claude 从相同基线构建的 `backend/target/opendataworks-backend-1.5.0.jar`，本轮 Java 源码未改。8080，临时匿名 admin 开关；禁用 Flyway、审计同步、新鲜度自动检查及工作流 runtime sync。前端正常开发模式 3017 代理 Java。验证结束停止本轮启动的两个服务。
- HTTP 200 / Result code 200：对象 272（business_domain）详情、字段、DDL、新鲜度、历史、血缘、任务、版本共 8 个只读接口。记录 `/tmp/odw-studio-review-api-smoke.json`。
- 正式页面：真实连接、Schema、表树加载；`SELECT COUNT(*) FROM opendataworks.business_domain` 返回 1 行；EXPLAIN 返回 MySQL 计划，编辑器 SQL 保留；`SELECT SLEEP(10)` 在运行时点停止，显示已停止 / SKIPPED，修正后耗时 5.67s，随后可再次执行。
- 任务抽屉：查询目标、SQL、描述预填正确，SQL 解析识别 business_domain 并填入输入表。目录为空明确提示调度连接配置；保存被数据源必填与输出表必填阻止，未创建任务。输出表必填与现有后端 DataTaskService 契约一致，本轮未改变只读 SQL 任务的业务模型。
- 引擎配置：直接编辑和取消可用，无分桶字段只读；新鲜度正常模式配置按钮在加载完成后可用并显示尚未配置。未提交 ALTER、元数据、字段或新鲜度写操作。
- 演示产物同步到原 4174 预览目录，保留原型图库文件。演示下配置与立即检查禁用、固定样例及历史可浏览。

## 后续事项与边界

1. 门户连接 + Schema + Dolphin 实例到调度数据源 id 的显式绑定，是后续独立功能。Claude 原方案只绑定 cluster/实例到名称，未覆盖多 Schema 和名称变更；设计文档已修正方向。本轮任务明确要求手动选择，不猜测对应关系。
2. 本地 Dolphin 12345 无监听、无既有运行容器，数据源 API 返回空数组。任务保存到调度、发布与执行未联调；需要可用 Dolphin 环境，再验证带输出表的 SQL 开发任务及流程。
3. 本地标为 DORIS 的连接实际上是 MySQL 3306，SHOW PARTITIONS 语法不支持；真实 Doris 分区、分桶/副本 ALTER 和引擎执行计划尚未验证。
4. 元数据/字段真实写入、新建/删除表、新鲜度配置 CRUD、智能元数据实际模型生成与采纳未进行真实提交。前端 API 接线及状态隔离由回归覆盖，不能描述成完整端到端验证。DataAgent 未启动、未调用真实模型。
5. 建议下轮使用专门测试连接及表完成写路径烟测；只读 SQL 保存为调度任务若需支持无输出表，应单独设计前后端任务契约，不能仅移除前端校验。

相关：[设计](../design/2026-10-09-datastudio-editor-workspace-design.md)、[计划](../plans/2026-10-09-datastudio-editor-workspace-plan.md)、[前轮验证](2026-10-09-datastudio-editor-workspace-validation.md)。

补充：[与未重构版本的功能及交互对照](2026-10-11-datastudio-before-after-comparison.md)，含旧版 170 项测试交叉验证与取消/迁移的交互清单。
