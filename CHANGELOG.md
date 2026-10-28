# Changelog

本文件记录 OpenDataWorks 的对外版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.5.0] - 2026-09-08

### Added
- 新增类 dbt 的数据新鲜度（freshness）检查：**表级契约**（时间字段取值方式 + warn/error 两级阈值 + 可选 filter，每张表各自声明），支持 `column` / `custom_sql` / `partition` / `metadata` 四种取值模式；检查结果留痕并可查询历史；提供表级契约 REST 接口、Data Studio「数据新鲜度」页签与巡检页只读新鲜度视图。
- 新鲜度检查采用**事件驱动**：工作流执行成功后即检查其写出的表（回答「任务报成功了，数据真的到了吗」；补数不触发），另提供页面按需触发。检查绑定到「运行」，不设时钟轮询。检查结果带触发实例ID，可按「每次运行」聚合并反查执行。
- 工作流详情页新增「数据新鲜度」页签：一眼看出该工作流写出表每次运行有几张表出问题，及逐表最新状态。

### Changed
- 数据新鲜度不再作为巡检规则。**行为变更**：`data_freshness` 巡检规则移除，freshness 成为独立的事件驱动子系统——不产生 `inspection_issue`、不参与每日巡检，红/黄状态活在 `table_freshness_result` 与 `data_table.freshness_status`（对齐 dbt 只写 `sources.json`、不建"问题"）。原规则从未随迁移种子化、也无配置入口，存量影响为零。

## [1.4.0] - 2026-06-26

### Changed
- 按照版本发布流程，发布 1.4.0 版本。

## [1.3.0] - 2026-06-04

### Changed
- 按照版本发布流程，发布 1.3.0 版本。

## [1.2.0] - 2026-05-11

### Added
- 新增 DolphinScheduler 引擎切换能力，支持 Dolphin 配置作用域隔离与发布链路适配。
- 新增主前端智能问数 Widget，并重组智能问数导航入口。

### Changed
- 简化 DataAgent Skill 运行时，统一 agent 查询限制与系统提示词方法论。
- 优化 `opendataagent` 导航、业务蓝配色、Skill 卡片密度与提供商配置交互。

### Fixed
- 修复 Dolphin 引擎切换后的发布导出、配置作用域与任务组 ID 失效问题。
- 修复只读聚合 SQL 校验、CSV 导出编码、DataAgent 运行目录权限与模型检测可选化相关问题。
- 修复 `opendataagent` 模型流错误暴露、管理员 token 代理、Compose 项目隔离与提供商弹窗操作可见性问题。

## [1.1.1] - 2026-04-24

### Fixed
- 修复 `opendataagent-web` 多架构 Docker 构建在 `linux/arm64` QEMU 环境执行 `npm ci` 时卡死并导致 Release 被取消的问题。

## [1.1.0] - 2026-04-21

### Added
- 新增独立 `opendataagent` 平台，包含 Go 后端、Vue 控制台、Skill / MCP / 模型设置与本地 Skill 市场能力。
- 新增 `opendataagent` 离线部署包与 Docker 镜像发布链路，GitHub Release 随版本同时发布 `opendataagent-server`、`opendataagent-web` 与离线包。
- 新增共享平台 Skill 源码目录与 `odw-cli`，供 `opendataagent` 复用 OpenDataWorks 元数据、血缘与只读 SQL 能力。
- 新增 DataAgent Skill 上传、卸载、多启用状态与运行时管理能力。

### Changed
- 重构智能问数 agent runtime 与 provider 配置保存流程，收敛运行时环境注入和模型服务配置体验。
- 优化主前端 Skill 管理页面、详情页、文件树与配置管理交互。
- 完善并行智能问数与 `opendataagent` 的部署边界说明，主部署继续保留现有 DataAgent 与 Portal MCP 链路。

### Fixed
- 修复 `opendataagent` Release 资产与镜像链接发布问题。
- 修复 issue #155，并补齐工作流元数据持久化与发布同步相关测试覆盖。

## [1.0.0] - 2026-04-14

### Added
- 新增 Portal MCP 服务与查询 API，并将其纳入 Docker 发布矩阵与离线部署包。
- 新增智能问数 mcp-first / backend-routed 执行链路，增强 lineage 工具优先与防护能力。
- 新增 DolphinScheduler 任务级 `flag` 全链路同步能力。

### Changed
- 重构智能问数运行时、历史恢复与工具轨迹 UI，提升话题恢复、活动展示与交互体验。
- 增强工作流发布预览与原始 JSON 差异展示，提升发布前可读性。
- 发布产物扩展为 frontend、backend、dataagent-backend、portal-mcp 四个镜像，并在离线包中包含 Redis 镜像。
- 项目许可证切换为 GPL-3.0-only。

### Fixed
- 修复 DataAgent 部署环境下 `odw-cli` 权限、可执行位与 Python 依赖相关问题。
- 修复管理员设置代理与遗留 provider 配置加载问题。
- 修复 Dolphin 发布链路 project code 持久化与 DDL copy fallback 问题。

## [0.8.0] - 2026-02-27

### Added
- 新增工作流软删除能力，支持可选级联处理任务关系。
- 新增 DataStudio schema 计数预加载与软删除对象过滤。
- 新增数据源创建后元数据同步提示，并支持空密码数据源创建。
- 新增同步历史详情中新增/修改/删除明细展示。

### Changed
- 解耦工作流保存流程与 Dolphin 元数据依赖，降低保存链路耦合。
- 增强工作流发布预览与元数据修复流程，统一任务组映射与差异行为。
- 改进工作流差异可读性：差异摘要可点击、同步 UX 简化并支持以 Dolphin 导入新工作流。

### Fixed
- 修复发布预览中的调度噪声与任务组解析问题。
- 修复 syncWorkflow 路径中工作流描述必填校验缺失问题。
- 修复 DataStudio 树展开稳定性、SQL 高亮范围异常与任务抽屉异常关闭问题。

## [0.7.1] - 2026-02-24

### Fixed
- 修复 V37 迁移脚本在 MySQL 上 `ADD COLUMN IF NOT EXISTS` 语法不兼容导致的 Flyway 执行失败问题。
- 调整 MinIO 关联字段与索引迁移为基于 `INFORMATION_SCHEMA` 的幂等条件执行。

## [0.7.0] - 2026-02-24

### Added
- 新增 MinIO 环境管理能力。
- 新增 Schema 备份配置、备份快照与恢复能力。
- 新增自动备份任务与配置项，支持备份流程集成。

### Changed
- 配置管理页面接入 MinIO 管理与 Schema 备份管理面板。

## [0.6.2] - 2026-02-24

### Changed
- 支持 `update-only` SQL lineage 更新策略。
- 加严 Dolphin 边关系校验逻辑，提升运行时同步一致性检查。

## [0.6.1] - 2026-02-13

### Changed
- 对齐 workflow version compare 与 export parity sync 逻辑。
- 增强版本比对持久化与运行时同步相关测试覆盖。

## [0.6.0] - 2026-02-13

### Added
- 完成 runtime sync 与版本治理端到端能力（预览、执行、差异、记录等）。

### Changed
- 工作流版本管理、回滚与比对链路增强。

## [0.5.0] - 2026-02-12

### Added
- DataStudio 新增 SQL 信息面板与语句级风险执行能力。

## [0.4.2] - 2026-02-12

### Fixed
- 修复分区元数据同步与超时相关问题。
- 统一分区命名字段，减少前后端字段不一致问题。

## [0.4.1] - 2026-02-12

### Changed
- 改进 DataStudio 血缘交互体验。
- 优化 Doris 元数据同步流程。

## [0.4.0] - 2026-02-11

### Added
- 新增以任务表为中心的血缘图视图能力。

### Changed
- 优化任务 SQL 自动解析与高亮交互。

### Fixed
- 支持手动 SQL 重新分析与表详情相关修复。

## 早期版本

- `0.3.7`、`0.3.6`、`0.3.5`、`0.3.4`、`0.3.3`、`0.3.2`、`0.3.1`、`0.3.0`
- `0.2.1`、`0.2.0`、`0.1.0`、`0.0.5`、`0.0.4`、`0.0.3`、`0.0.2`、`0.0.1`

以上早期版本请参考 GitHub Releases 与 Git tags 历史记录。

[Unreleased]: https://github.com/opendata-lab/opendataworks/compare/v1.5.0...HEAD
[1.5.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v1.5.0
[1.4.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v1.4.0
[1.3.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v1.3.0
[1.2.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v1.2.0
[1.1.1]: https://github.com/opendata-lab/opendataworks/releases/tag/v1.1.1
[1.1.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v1.1.0
[1.0.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v1.0.0
[0.8.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.8.0
[0.7.1]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.7.1
[0.7.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.7.0
[0.6.2]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.6.2
[0.6.1]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.6.1
[0.6.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.6.0
[0.5.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.5.0
[0.4.2]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.4.2
[0.4.1]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.4.1
[0.4.0]: https://github.com/opendata-lab/opendataworks/releases/tag/v0.4.0
