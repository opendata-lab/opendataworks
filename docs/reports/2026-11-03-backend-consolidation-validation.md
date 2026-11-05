# Backend 收敛实施与本机验证

- 日期：2026-11-03
- 对应 PR：[#493](https://github.com/opendata-lab/opendataworks/pull/493)
- 设计：[主设计](../design/2026-11-03-backend-mcp-auth-consolidation-design.md)、[清理设计](../design/2026-11-03-backend-legacy-redundancy-cleanup-design.md)
- 计划：[主计划](../plans/2026-11-03-backend-mcp-auth-consolidation-plan.md)、[清理计划](../plans/2026-11-03-backend-legacy-redundancy-cleanup-plan.md)
- 本地启动：[运行说明](../handbook/backend-runtime.md)

## 实施结果

后端已迁至 `backend/{core,api,mcp-server,app}`，唯一 Reactor 为 `backend/pom.xml`。锁定 Java 17、Spring Boot 3.5.7、MCP SDK 0.18.5 和 SDK 配套的 NetworkNT 2.0.0；只有 app 打包启动。删除顶层认证工程、Agent REST 中转工程、Python MCP 服务及已确认废弃接口，保留现有包名和独立 DataAgent 仓库边界。

身份只有不可变 CurrentPrincipal 与支持嵌套恢复的 PrincipalContextHolder。app 统一处理 USER / SERVICE 路由、显式 Bearer 优先、Origin、私网和可信代理链。MVC 路由异常保留 404 / 405 与 Allow 头，避免 Boot 3 资源处理器将退役路由误报为 500；新增 3 项回归。MCP 使用官方无状态 MVC 传输，两个尾斜杠路径都直接接受 POST；23 个工具共用一份严格 Schema，顶层 camelCase 参数，描述与 Skill 配方同步。工具直接调用领域服务，结果 DTO 作为顶层 structuredContent 暴露。

DatasourceSubjectAccessService 只选择主体账号与元数据可见性交集，保留连接工厂。物理 DDL 使用当前 USER / SERVICE 自身映射；可选用户头仅收窄读权限。后台采集、检查和备份显式请求系统连接。权限管理页允许 SERVICE 被明确授予写角色，READONLY 模板仍限制写动作。平台身份、Token、审计及权限 SQL 统一 Mapper；权限页面既有动态 Map 响应契约保留，不引入重复 REST DTO 转换。

DataQueryService 统一只读检查、委托 EXPLAIN、主体执行、128 KiB 完整行预算与最终结果审计。超大单行不改写原值，返回空行集和明确通知；MCP 仅检查 512 KiB 包装预算，不二次删行或重试业务。发布不再签发或消费预览 Token，领域执行时重做预检，confirmDiff 缺省 false。Dolphin 目标配置单次解析后显式传递，RestClient 替换 WebClient；SDK 正常 Reactor 传递依赖保留。

部署、离线打包、镜像构建、Nginx、环境模板、仓库说明和网站文档已切换到 Java 内置 MCP。镜像直接构建四模块 Reactor，使用 Maven 下载缓存；不运行独立依赖预热来解析尚未构建的内部模块。

## 已通过验证

| 验证 | 结果 |
|---|---|
| Java 四模块单元 / 服务 / SQL / 边界测试 | 596 项，零失败、错误、跳过；core 551 / api 18 / mcp-server 6 / app 21 |
| 真实 WorkflowRuntimeSyncRealIntegrationTest | 7 项，零失败、错误、跳过 |
| Vue 全量 Vitest | 67 文件 / 480 项通过 |
| 前端生产 Vite 构建 | 通过；保留现有大 chunk / Sass 弃用提示 |
| Python 部署、离线包及 Skill 契约 | 13 项通过，无跳过；使用专用 Python 3.12 venv 和 xz |
| service_accounts_smoke.py | 26 项真实 HTTP / MySQL / MCP 验收通过 |
| backend_mcp_smoke.py | 11 组验收通过，经真实 HTTP 命中全部 23 个 handler |
| legacy_identity_headers_smoke.py | 6 项验收通过 |
| Shell / Compose | scripts 下及后端集成入口 bash -n；开发 / 生产 Compose config 通过 |
| 后端 Docker 镜像 | 构建通过；镜像启动、Flyway、健康、登录、双 MCP 路由及 23 工具目录通过；临时验证容器已清理 |
| Flyway | 新建 MySQL 平台库迁移至 V57；重启校验全部 57 份迁移且无需再次迁移 |
| 浏览器 | 管理员登录、控制台、数据源、账号页和 Data Studio 本地查询通过（orders 返回 2 行） |

Java 命令：`mvn -B -ntp -f backend/pom.xml -Dtest='*Test,!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false package`。真实工作流命令：`backend/scripts/run-integration-test.sh`，设置 DS_BASE_URL / DS_TOKEN / DS_PROJECT_NAME / IT_MYSQL_HOST。前端命令均先成功执行 nvm use（20.19.0），随后 npm --prefix frontend test / run build。Python 命令：专用 venv 的 python -m pytest tests odw-skills/tests -q。

真实 MySQL 检查包括 USER 原生查询、多个 SERVICE 角色跨库 JOIN、可选用户权限交集、伪造旧身份头不改变主体、无映射拒绝、原生写角色分配、Token 摘要存储/到期/撤销/停用/删除失效。MCP 检查包括双路由、GET/DELETE 405、无会话头、旧包装与未知参数拒绝、超大查询行通知、顶层 rows、只读 SERVICE 物理 ALTER 被拒绝、授予 ALTER 后即使委托用户无效仍使用 SERVICE 自身账号，以及任务/工作流草稿、发布、上线、定时调度启停。Doris 建表预览通过；MySQL 数据源调用 Doris 专属建表明确拒绝，未将其算作真实 Doris 建表成功。

真实 Dolphin 3.2.2 执行包含六任务串并行汇聚 DAG，核对目标 MySQL 行数及汇总金额；覆盖新增/删除节点、SQL 和依赖演进、反向同步、版本/血缘、导入导出和差异决策。环境镜像缺少 MySQL 驱动，补入 Connector/J 8.0.33 后运行通过。修正集成 fixture 的显式导入目标与往返导入重名，删除仅供测试且该版本不存在的 basic-info 生产死方法，测试使用既有完整定义更新接口。

## 留给本机试用的环境

- 门户：`http://127.0.0.1:3000/`，初始管理员 admin / admin123。
- 后端：`http://127.0.0.1:8082/api/v1/health`，MCP `/api/mcp/`。
- MySQL：本轮独立容器 odw-consolidation-mysql，127.0.0.1:3306。
- Dolphin：本轮独立容器 odw-consolidation-dolphin，`http://127.0.0.1:12345/dolphinscheduler`。
- 保留“本地演示 MySQL”数据源和 orders 示例表，管理员使用独立只读原生账号映射，可在 Data Studio 展开演示源执行 `SELECT id, amount FROM orders ORDER BY id`。测试状态与凭据放在仓库外的私有文件中；可用验收脚本 --cleanup 清理。
- 本地 backend 运行已构建 jar 的独立副本，避免后续 Maven 打包覆盖正在运行的 jar。

## 覆盖边界与发布

本轮未启动独立仓库 DataAgent，也未执行真实模型问答、最终消息持久化或其生产沙箱容器链路；HTTP MCP 协议与工具行为已单独验证。PostgreSQL / Doris 真实权限与 DDL、生产数据库快照升级及生产代理网络拓扑未在本机重跑，不能由 MySQL / SQL 契约替代。上述覆盖缺口已登记[统一待办](../TODO.md)。历史浏览器可信委托和平台功能级 RBAC 仍保留原待办，不由本轮访问身份合并自动解决。

升级须同步替换 Java / Vue 镜像，更新外部 MCP URL 和工具 Schema 缓存，使用管理中心签发的 SERVICE Token；旧 Python 服务、共享内部 Token、Agent REST URL 和 snake-case / params 包装不兼容。先备份平台库、配置和镜像版本。回退时恢复成套旧镜像与外部调用配置，不混用新旧工具契约；本轮未增加 Flyway 迁移，不对线上数据执行 repair 或重建。

## 授权管理连接权限不足提示补充（2026-11-03）

用户在新建角色的授权预览中遇到 MySQL 1142：采集账号无权读取 mysql.user。权限管理错误现在明确提示当前账号权限不足，并指向当前数据源「权限配置 → 授权管理连接」，说明应配置专用管理账号或联系 DBA 补齐系统授权表读取、角色管理和目标对象授权权限，同时保留数据库原始错误。

错误分类采用 MySQL 权限错误码及 PostgreSQL insufficient_privilege SQLState；语法、登录认证及网络错误不归入权限不足。授权记录中的 FAILED / PARTIAL / UNKNOWN 状态及步骤不改变，资源盘点错误也保留同样指引和原始异常。成员自身权限检查仍沿用成员连接诊断。同页权限手册同步移除已失效的 SERVICE 恒定只读／开发接口 403 说明。

验证：DatasourcePermissionErrorMessageTest 新增 11 项，连同 DatasourcePermissionInspectionTest、NativePermissionInspectionTest 共 20 项通过；四模块重新打包成功。已在运行中的本机后端通过真实 MySQL 无系统表读取权限账号触发角色预览 HTTP 400，确认响应包含设置路径和 SELECT command denied 原始信息，随后恢复测试源原有管理连接并删除临时角色。本地后端已重启到新包，未修改采集账号权限。
