# 内部文档

本目录存放 OpenDataWorks 的内部开发文档，不对外发布。

面向用户的公开文档请访问：https://opendataworks.vercel.app/

## 目录结构

| 目录 | 用途 |
|------|------|
| `design/` | 中大型变更的设计文档 |
| `plans/` | 与设计文档配套的实施计划 |
| `handbook/` | 长期维护的手册、特性专题与内部指南 |
| `reports/` | 修复报告、测试结果、阶段总结 |
| `TODO.md` | 待办清单：已确认但暂不处理的事项，统一记录在这里 |

## 使用约定

- 中大型变更先写 `design/`，再写 `plans/`
- `handbook/` 承载稳定手册和专题说明
- `reports/` 记录执行结果、问题复盘和阶段性总结
- 待办统一记录到 `TODO.md`，按需逐项完善；开始做某一条时，中大型变更先补设计和计划
- 命名规则：`YYYY-MM-DD-<topic>-design.md` / `YYYY-MM-DD-<topic>-plan.md`
