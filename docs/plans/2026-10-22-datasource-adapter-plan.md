# 数据源适配器实施计划

日期：2026-10-22。状态：**阶段一、二已实施，验证范围见报告（2026-10-25）**。

设计：[数据源适配器架构](../design/2026-10-22-datasource-adapter-design.md)。本轮实施阶段一、二；阶段三、四独立后续实施。

## 阶段一：基础接入适配器

1. 固定 `DatasourceDescriptor`、`ConnectionConfig`、`Namespace`、表/字段/统计 DTO 与错误码；明确 type 字符串、密码保护、字段约束和未知统计的语义。
2. 新增 `service/datasource/` 下 Adapter、MetadataReader、SqlDialect、Registry；Spring 自动注册，拒绝重复类型，校验能力与 handler 实现一致。
3. 迁移 Doris/MySQL/PG 的 URL、会话初始化、系统 namespace、元数据读取和 SQL 方言；复用已验证的 MySQL 协议公共工具。`DorisConnectionService` 暂保留兼容门面，逐项委托，不做无关改名。
4. `DorisClusterService` 使用注册器配置校验，移除基础类型白名单；新增 `DatasourceTypeController` 类型发现接口，配置 DTO 附类型说明。
5. `DorisMetadataSyncService` 消费统一 DTO，保留差异、版本和同步历史编排，剥离由 adapter 负责的目录过滤；未知统计不覆盖已知数据。
6. 若实施连接扩展字段，检查最新 Flyway 后分配新迁移，增加可空 `connection_options`；原 Doris/MySQL/PG 配置无需改写。
7. 增加测试专用新 type 适配器，验证基础扩展无需中央枚举或业务服务变更。
8. 新增 driver 模块声明 driverClass 并报告可用/缺失，执行前检查；保留未来 driverId、manifest、Java/依赖检查和独立 ClassLoader 的边界。完整外置加载记入 TODO，本轮不增加 driver_id 或连接扩展字段。
9. 保留 backend/pom.xml 中 Doris/MySQL/PostgreSQL 内置驱动及平台元数据库启动布局。外置目录仅用于新增 JDBC 数据库或显式覆盖版本，后续实施；升级不准备目录、不做旧源版本绑定迁移。

主要文件：`backend/src/main/java/com/onedata/portal/service/datasource/`、`DorisConnectionService`、`DorisClusterService`、`DorisMetadataSyncService`、配置 DTO/controller、新鲜度能力校验及 Java 契约测试。当前不需要修改 pom 或 Flyway。

## 阶段二：前端消费描述

1. 数据源管理从类型 API 生成选项、默认端口、连接字段与必填校验；没有可用类型说明时给出配置加载失败，不回退为静态列表。
2. 目录及表页签透传类型说明；基本信息与字段按通用接口展示，不通过 `isSynced` 推断引擎。
3. 默认 SELECT/预览由后端 dialect 生成；编辑器高亮按描述使用已支持方言，未知方言采用标准 SQL。保留用户已编辑的 SQL。
4. 以能力、对象约束及权限决定 DDL/编辑/备份可用性；后端仍验证能力。无法导出 DDL 不影响表结构和基本信息。
5. 增加虚拟新类型描述的组件回归，证明不用添加新类型分支。
6. 表单展示描述中的内置驱动可用/缺失状态；缺失时禁用保存/连接并说明原因。本轮不提供尚未实现的 driverId 选择，外置加载与选择器作为后续项。

主要文件：`frontend/src/api/doris.js`、`DataSourceManagement.vue`、Data Studio catalog/tab/query composables、`DataStudioRightPanel*`、`SqlEditor.vue` 及相关测试。保持 Vue/Element Plus 当前布局，不引入视觉重构。

## 阶段三：物理编辑扩展（独立可选）

复用 `TableEngineHandler` 并连接到 capability 校验。原 MySQL 主键限制、Doris 分桶/副本等规则必须保留。新增某数据库编辑能力时实现对应 handler 与真实 DDL 测试；不要求每个基础适配器同时支持物理变更。

## 阶段四：DataAgent 入口统一（单独验收）

1. 审计 `AgentJdbcExecutor`、`BackendAgentMetadataService`、Python resolver 及技能实际执行路径。
2. Java agent API 复用基础适配器，保留 scope、只读校验和超时；Python 消费明确类型与统一工具，删除 MySQL/Doris 二选一推断。
3. 更新 Java agent API DTO、Python 工具契约和对应技能说明；保持共享 runtime 与数据库/技能无关。
4. 按仓库规则启动 MySQL、Redis、DataAgent、portal MCP 与必要前端，使用真实 provider 的真实 NL2SQL 请求验证任务、事件、消息持久化与渲染。没有全流程结果不能标为智能查询适配完成。

## 验证门槛

- Java 定向测试：注册/重复/未知类型、配置校验、连接关闭、凭据隔离、统一元数据、能力拒绝、原三种引擎回归。
- 前端：每次命令前 `nvm use`，运行受影响 composable/组件测试和生产构建。
- 真实 PostgreSQL：独立容器和平台 MySQL 测试 schema，实际 HTTP 走配置 → 连接 → 同步 → 历史 → 表基本信息/字段 → analyze/execute → 查询历史。补充真实浏览器操作验证新表单、字段面板和查询结果。
- 真实 MySQL/Doris：按可用环境运行原功能冒烟；环境不可用时单独列出缺失覆盖，不以 mock 代替真实执行宣称通过。
- 扩展性：新增测试适配器仅带驱动/私有实现/测试即可被全链路识别；前端不得新增类型列表或引擎判断。
- 驱动：本轮验证内置驱动可用/缺失及错误类报告、旧配置兼容。外置包、Java/校验和、双版本隔离与版本选择留待外置加载独立验收。
- 文档：更新数据源接入说明和能力矩阵、验证报告，确保描述与已实现能力一致。

## 发布与回退

阶段一、二作为一个兼容发布：先发布后端（描述 API、旧 API 门面），后前端。保留现有内置驱动，无 schema 或驱动版本迁移，无需新增挂载目录。阶段三、四独立发布和验收。

回退前关闭后续新引擎自动同步和查询入口，保留原有配置，回退应用版本。本轮只有原三种生产适配器，没有新增列。保持旧三种数据源的字段和类型值不变，不做配置破坏性转换。

本轮回退只需恢复原应用与前端版本，驱动依赖及启动布局不变。后续外置加载发布另行验证 manifest、版本绑定及驱动包的回退。

## 当前状态

阶段一、二代码及契约验证已完成；真实 Doris 和 DolphinScheduler 环境缺口保留在 TODO。内置为主、外置可选已经决定。本轮不更改 DataAgent 或物理编辑实现，不新增 Maven 子模块。验证结果见 [验证报告](../reports/2026-10-25-datasource-adapter-verification.md)。
