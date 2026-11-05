# 角色矩阵定位、可读预览与模板编辑（PR 3）

日期：2026-11-05。范围仅限[计划 PR 3](../plans/2026-11-05-access-control-center-plan.md#pr-3角色矩阵定位可读预览与模板编辑)，遵循[设计](../design/2026-11-05-access-control-center-design.md)。无迁移、无部署变更，不涉及其他 PR 功能。

## 实现

- PATCH 角色允许单独或同时更新名称、模板；READONLY 校验平台已保存授权，含写入／结构动作时拒绝。角色更新递增 revision，使已生成预览失效。保留 preview 的只读校验。
- preview 增加 changes，按资源合并动作并返回 ADD／REMOVE／UPDATE、变更前后与增删动作。此列表比较平台已保存业务授权与请求；原生漂移仍由既有警告与实际 SQL 表达。
- 矩阵显示已保存授权资源数、待变更资源数；数据库选项包含已保存／草稿涉及的库。跨库模式忽略数据库筛选，由已保存授权与草稿合成行，保留撤销行，不额外请求其他库的资源目录。
- 按模板勾选当前搜索／类型范围内的列表：READONLY／CUSTOM 查询及适用的视图定义，READWRITE 查询／写入，ADMIN 当前资源支持的全部动作；不取消既有动作。保存栏统计新增／撤销动作项。
- 编辑角色支持模板选择；预览主视图展示业务动作差异，SQL 默认折叠。复用现有 composable、共享标签与权限 API。

## 验证

- 后端：`mvn -o -pl core,api -am test -Dtest='*Permission*,SubjectAccess*' -Dsurefire.failIfNoSpecifiedTests=false`：37 tests，0 failures，0 errors，0 skipped；其中新增角色编辑／差异测试 4 项。
- 前端：先成功 `nvm use`（Node 20.19.0），`npx vitest run src/views/settings --cache=false`：9 文件、83 tests 全部通过，其中新增 8 项。关闭缓存是因为 node_modules 为指向其他 worktree 的符号链接，默认缓存写入被 sandbox 拒绝。
- `npx vite build`：通过；既有 Sass legacy API／大 chunk 提示保留。
- `npx eslint <全部 7 个变更的前端文件>`：通过，0 条输出。
- 新 Java 文件使用指定 google-java-format（AOSP）格式化；`git diff --check` 通过。
- 隔离临时 PostgreSQL 16 容器、临时 Java 主程序使用本分支编译的 DatasourcePermissionService.preview/apply；实际 PG JDBC、原生适配器与角色读回，平台 mapper／源锁／事务为 mock。preview 返回 ADD TABLE sales.orders QUERY，执行 CREATE ROLE／GRANT USAGE／GRANT SELECT 后 apply SUCCESS、触发平台 activateRole 与事务 commit；PG `has_table_privilege` 核验 SELECT=true、INSERT=false。容器随后删除。

首次前端运行发现视图 model 透传遗漏与弹窗异步测试问题，已修复后重跑通过；另一次命令误从仓库根目录启动，在 npm 缓存权限阶段失败，未安装依赖，随后从 frontend 目录正确重跑。

## 验证边界

未重启现有 8082 后端或 3000 dev server；未运行完整 HTTP／浏览器闭环。该缺口记录在[统一待办](../TODO.md#权限中心剩余验证与边界)。本次未运行其他测试套件，未添加测试基础设施；原生烟测为临时程序，未提交到仓库。待 Claude 核实与 PR 评审，未合并。
