# 数据源适配器实施计划

日期：2026-10-22。状态：**待评审，未开始实施**。

设计：[数据源适配器架构](../design/2026-10-22-datasource-adapter-design.md)。本轮只交付方案，不继续代码改造。

## 阶段一：基础接入适配器

1. 固定 `DatasourceDescriptor`、`ConnectionConfig`、`Namespace`、表/字段/统计 DTO 与错误码；明确 type 字符串、密码保护、字段约束和未知统计的语义。
2. 新增 `service/datasource/` 下 Adapter、MetadataReader、SqlDialect、Registry；Spring 自动注册，拒绝重复类型，校验能力与 handler 实现一致。
3. 迁移 Doris/MySQL/PG 的 URL、会话初始化、系统 namespace、元数据读取和 SQL 方言；复用已验证的 MySQL 协议公共工具。`DorisConnectionService` 暂保留兼容门面，逐项委托，不做无关改名。
4. `DorisClusterService` 使用注册器配置校验，移除基础类型白名单；新增 `DatasourceTypeController` 类型发现接口，配置 DTO 附类型说明。
5. `DorisMetadataSyncService` 消费统一 DTO，保留差异、版本和同步历史编排，剥离由 adapter 负责的目录过滤；未知统计不覆盖已知数据。
6. 若实施连接扩展字段，检查最新 Flyway 后分配新迁移，增加可空 `connection_options`；原 Doris/MySQL/PG 配置无需改写。
7. 增加测试专用新 type 适配器，验证基础扩展无需中央枚举或业务服务变更。
8. 新增 driver 模块：外置目录/manifest、驱动描述、Java/依赖检查、Driver 实例加载及 driverId 绑定。数据源新增可空 driver_id，版本号在实施时分配；现有数据源由明确升级配置绑定驱动，禁止隐式使用平台驱动或最新版。
9. 调整用户数据源 JDBC 驱动的打包/部署方式：独立目录挂载，应用启动加载；不增加每个厂商驱动的主程序 runtime 依赖。平台元数据库驱动独立处理，第一阶段保留现状；若也外置，另验证 Spring/Hikari/Flyway 的启动加载顺序与发行包布局。

主要文件：`backend/src/main/java/com/onedata/portal/service/datasource/`、`DorisConnectionService`、`DorisClusterService`、`DorisMetadataSyncService`、配置 DTO/controller、`backend/pom.xml`、必要 Flyway 与 Java 契约测试。

## 阶段二：前端消费描述

1. 数据源管理从类型 API 生成选项、默认端口、连接字段与必填校验；没有可用类型说明时给出配置加载失败，不回退为静态列表。
2. 目录及表页签透传类型说明；基本信息与字段按通用接口展示，不通过 `isSynced` 推断引擎。
3. 默认 SELECT/预览由后端 dialect 生成；编辑器高亮按描述使用已支持方言，未知方言采用标准 SQL。保留用户已编辑的 SQL。
4. 以能力、对象约束及权限决定 DDL/编辑/备份可用性；后端仍验证能力。无法导出 DDL 不影响表结构和基本信息。
5. 增加虚拟新类型描述的组件回归，证明不用添加新类型分支。
6. 表单增加 driverId 选择和驱动缺失/不兼容提示；第一阶段仅从已挂载驱动包选择，不包含网页上传或 Maven 自动下载。

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
- 驱动：外置 JDBC 单包/多依赖包、缺失/错误类/JDK不兼容/校验错误、明确版本绑定及双版本隔离；验证平台元数据库连接和 Flyway 不受影响。更换挂载驱动并重启无需重新打包应用。
- 文档：更新数据源接入说明和能力矩阵、验证报告，确保描述与已实现能力一致。

## 发布与回退

阶段一、二作为一个兼容发布：先准备外部驱动目录、manifest 和现有源的版本绑定，再发布后端（可空配置字段迁移、描述 API、旧 API 门面），后前端。阶段三、四独立发布和验收。

回退前关闭新引擎自动同步和查询入口，保留新增可空列及原有配置，回退应用版本。保持旧三种数据源的字段和类型值不变，不做配置破坏性转换。

外置驱动目录、manifest 与原驱动包一并保留；回退时恢复明确的版本绑定和相应启动布局，不能仅回退数据库字段后依赖 classpath 偶然选中旧驱动。

## 当前状态

适配器改造未实施；未新增运行依赖或平台 schema。用户要求先出方案后，已撤回未接入的适配器草稿并停止/清理临时 HTTP 测试实例。之前 PostgreSQL 修改、验证记录及查询页局部修复不属于此架构方案的已实施成果。
