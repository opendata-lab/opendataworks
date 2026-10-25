# 数据源适配器实施计划

日期：2026-10-22。状态：**阶段一、二已实施；阶段三、Java 阶段四及连接字段扩展已实施（2026-10-26，验证见报告）**。

设计：[数据源适配器架构](../design/2026-10-22-datasource-adapter-design.md)。本轮续作阶段三与 Java 阶段四；Python 与外置加载继续独立后续实施。

## 阶段一：基础接入适配器

1. 固定 `DatasourceDescriptor`、`ConnectionConfig`、`Namespace`、表/字段/统计 DTO 与错误码；明确 type 字符串、密码保护、字段约束和未知统计的语义。
2. 新增 `service/datasource/` 下 Adapter、MetadataReader、SqlDialect、Registry；Spring 自动注册，拒绝重复类型，校验能力与 handler 实现一致。
3. 迁移 Doris/MySQL/PG 的 URL、会话初始化、系统 namespace、元数据读取和 SQL 方言；复用已验证的 MySQL 协议公共工具。`DorisConnectionService` 暂保留兼容门面，逐项委托，不做无关改名。
4. `DorisClusterService` 使用注册器配置校验，移除基础类型白名单；新增 `DatasourceTypeController` 类型发现接口，配置 DTO 附类型说明。
5. `DorisMetadataSyncService` 消费统一 DTO，保留差异、版本和同步历史编排，剥离由 adapter 负责的目录过滤；未知统计不覆盖已知数据。
6. 若实施连接扩展字段，检查最新 Flyway 后分配新迁移，增加可空 `connection_options`；原 Doris/MySQL/PG 配置无需改写。
7. 增加测试专用新 type 适配器，验证基础扩展无需中央枚举或业务服务变更。
8. 新增 driver 模块声明 driverClass 并报告可用/缺失，执行前检查；保留未来 driverId、manifest、Java/依赖检查和独立 ClassLoader 的边界。完整外置加载记入 TODO；阶段一不增加 driver_id 或连接扩展字段。本轮阶段三以 V53 实施连接选项，driver_id 仍留待后续。
9. 保留 backend/pom.xml 中 Doris/MySQL/PostgreSQL 内置驱动及平台元数据库启动布局。外置目录仅用于新增 JDBC 数据库或显式覆盖版本，后续实施；升级不准备目录、不做旧源版本绑定迁移。

主要文件：`backend/src/main/java/com/onedata/portal/service/datasource/`、`DorisConnectionService`、`DorisClusterService`、`DorisMetadataSyncService`、配置 DTO/controller、新鲜度能力校验及 Java 契约测试。阶段一、二不需要修改 pom 或 Flyway；本轮阶段三增加 V53，不改 pom。

## 阶段二：前端消费描述

1. 数据源管理从类型 API 生成选项、默认端口、连接字段与必填校验；没有可用类型说明时给出配置加载失败，不回退为静态列表。
2. 目录及表页签透传类型说明；基本信息与字段按通用接口展示，不通过 `isSynced` 推断引擎。
3. 默认 SELECT/预览由后端 dialect 生成；编辑器高亮按描述使用已支持方言，未知方言采用标准 SQL。保留用户已编辑的 SQL。
4. 以能力、对象约束及权限决定 DDL/编辑/备份可用性；后端仍验证能力。无法导出 DDL 不影响表结构和基本信息。
5. 增加虚拟新类型描述的组件回归，证明不用添加新类型分支。
6. 表单展示描述中的内置驱动可用/缺失状态；缺失时禁用保存/连接并说明原因。本轮不提供尚未实现的 driverId 选择，外置加载与选择器作为后续项。

主要文件：`frontend/src/api/doris.js`、`DataSourceManagement.vue`、Data Studio catalog/tab/query composables、`DataStudioRightPanel*`、`SqlEditor.vue` 及相关测试。保持 Vue/Element Plus 当前布局，不引入视觉重构。

## 阶段三：连接、物理编辑与配置扩展（2026-10-26）

1. 新增统一连接工厂；DorisConnectionService 保留凭据映射，TableEngineJdbcExecutor 委托门面；所有 URL/驱动校验/会话初始化通过适配器，保持 URL 安全校验。
2. handler/registry 统一归一化字符串，移除枚举及服务调用点；编辑能力门禁与原有主键/物理规则保持。
3. 定义能力目录并拒绝未知能力，补全缺省拒绝；前后端将 dorisProperties 改为 engineProperties，前端新增基线名称校验。
4. 审计备份、inspection、同步、审计服务与 DolphinScheduler 类型分支；用实际能力替换，记录保留的外部产品协议默认值。
5. 检查当前最大 V52 后增加 V53 可空 connection_options；实现声明字段白名单、值校验、密码脱敏和更新保留、动态表单。TEST_DB 验证保存到取连接完整链路，内置源无需新增字段。
6. 在适配器构造时缓存描述、方言与元数据读取器。

## 阶段四：Java agent API 统一（2026-10-26）

1. AgentJdbcExecutor 复用连接工厂，保留只读、schema、maxRows、timeout；不移动/放松只读语句、数据范围和凭据授权校验。
2. 描述增加 agentEngine，BackendAgentMetadataService 从注册表读取；resolution 透传非任意参数形式的受控连接选项。
3. 将 PostgreSQL 参考 DDL 放入适配器参考结构 SPI，保持 ddlExport 不支持。
4. 运行 agent、映射、编辑、注册表契约回归；真实 PG 集成含 agent 只读查询。
5. Python resolver/tool 与真实模型 NL2SQL 不在本轮；Python 保留 TODO，真实模型因账号欠费记录未验证，不宣称全链路完成。

## 验证门槛

- Java 定向测试：注册/重复/未知类型、配置校验、连接关闭、凭据隔离、统一元数据、能力拒绝、原三种引擎回归。
- 前端：每次命令前 `nvm use`，运行受影响 composable/组件测试和生产构建。
- 真实 PostgreSQL：独立容器和平台 MySQL 测试 schema，实际 HTTP 走配置 → 连接 → 同步 → 历史 → 表基本信息/字段 → analyze/execute → 查询历史。补充真实浏览器操作验证新表单、字段面板和查询结果。
- 真实 MySQL/Doris：按可用环境运行原功能冒烟；环境不可用时单独列出缺失覆盖，不以 mock 代替真实执行宣称通过。
- 扩展性：新增测试适配器仅带驱动/私有实现/测试即可被全链路识别；前端不得新增类型列表或引擎判断。
- 驱动：本轮验证内置驱动可用/缺失及错误类报告、旧配置兼容。外置包、Java/校验和、双版本隔离与版本选择留待外置加载独立验收。
- 文档：更新数据源接入说明和能力矩阵、验证报告，确保描述与已实现能力一致。

## 发布与回退

阶段一、二（历史发布）无 schema 或驱动版本迁移。本轮阶段三/Java 阶段四先运行 V53 可空列迁移，再协调部署后端与前端，以同步切换 engineProperties 能力名。保留内置驱动和旧配置 API，旧三种配置无需补值或绑定版本。

回退应用与前端时保留 connection_options 列，不执行破坏性 down migration；暂停旧版本无法识别的新适配器配置。三个内置源仍用原字段，驱动依赖与启动布局不变。外置加载的 manifest、绑定与包回退留待后续独立验收。

## 当前状态

阶段一、二已完成；阶段三与 Java 阶段四代码及本轮验证已完成，既有环境失败和未覆盖项见报告，Python 与外置驱动加载未纳入本轮。真实 Doris、DolphinScheduler 和真实模型环境缺口保留在 TODO。不新增 Maven 子模块。验证结果见 [验证报告](../reports/2026-10-25-datasource-adapter-verification.md)。

## 本轮验证、发布与回退增补（2026-10-26）

- 定向测试后使用临时 central mirror settings 执行 `mvn -s <settings> -o -q -pl backend -am test`，逐项确认七个已知 DolphinScheduler 失败且无新增；记录各模块 Tests/Failures/Errors/Skipped。
- Podman 临时 PG 使用非 55440 端口，设置 ODW_POSTGRES_TEST_PORT 跑 PostgresqlDatasourceIntegrationTest，完成清理。
- 仅独立 MySQL schema：从零跑 V1..V53；另建 V52 带数据 schema 的副本跑 V53，验证旧字段与数据不变，最后清理。
- 启动隔离配置的 Java backend，通过真实 HTTP 在 PG/MySQL 验证 agent 查询、写语句拒绝、越界引用拒绝及结构编辑能力允许/拒绝。
- 前端 nvm use 后 test/build，不重新 install；补充能力名与后端基线一致性、额外字段控件回归。
- 升级先加可空列，前后端协调发布能力重命名；回退保留列并停止旧应用不支持的新类型。内置驱动不变，未配置外置目录仍无需任何准备。
- 更新既有报告的 2026-10-26 章节、TODO 已完成与剩余事项；不 commit、不 push。
