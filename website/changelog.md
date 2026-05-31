# 更新日志

本页面记录 OpenDataWorks 的版本迭代历史。完整的提交记录请查看 [GitHub Releases](https://github.com/opendata-lab/opendataworks/releases)。

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)规范，条目分类参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)：`新增`、`变更`、`修复`、`移除`。

## 未发布 (Unreleased)

> 下个版本的改动将在此累积，发布时归入对应版本号。

## v1.0.0

首个公开发布版本。

### 新增

- **数据资产管理**：可视化建表、字段维护、物理 DDL 同步（Apache Doris）
- **生命周期管理**：表级软删除回收站，支持 30 天宽限期与一键恢复
- **存储与热度分析**：表存储指标趋势、访问热度统计、闲置表预警
- **工作流编排**：可视化 DAG 设计、发布审批、补数据、DolphinScheduler 集成
- **数据血缘**：自动血缘解析、力导向拓扑图、上下游穿透分析
- **智能查询**：基于 AI 的 NL2SQL 对话式分析，自动生成 SQL 与图表
- **部署能力**：Docker Compose 一键部署，支持开发与生产环境、离线部署包

---

::: tip 如何记录改动
提交 PR 时，请在「未发布」小节追加一条对应分类的条目，并在描述中说明影响范围。维护者在发布时会将这些条目归并到新的版本号下。
:::
