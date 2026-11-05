# 分配角色自动创建数据库登录账号：实施与验证

- 日期：2026-11-04
- PR：[#493](https://github.com/opendata-lab/opendataworks/pull/493)
- 设计：[主体数据库身份设计 §15](../design/2026-10-26-dataagent-datasource-identity-design.md#15-分配角色时自动创建数据库登录账号2026-11-03)
- 计划：[实施计划 §11](../plans/2026-10-26-dataagent-datasource-identity-plan.md#11-自动创建数据库登录账号2026-11-03)

## 当前行为

管理员配置一次「授权管理连接」，给 USER 或 SERVICE 首次分配角色时，平台自动创建独立原生登录并生成密码，不再要求 DBA 逐人建号、手动绑定。多个角色共用同一账号，后续添加／撤销角色不重建登录或改密码。MySQL／Doris 使用 CREATE USER，PostgreSQL 使用具有 LOGIN / INHERIT、无管理属性的 ROLE。

V58 给 datasource_subject_account 增加 managed 标记，既有映射默认 false；已有 DBA 账号及密码表示不变。新 managed 密码用既有 ODW_CREDENTIAL_KEY 加密，在两处集中连接入口解密。前端不输入、不展示生成密码；响应、操作步骤和异常不包含密码。原生 CREATE 通过局部 JDBC 执行，避开通用 SQL 日志；授权记录只显示「CREATE LOGIN …（密码已隐藏）」。脱敏保留原生 SQLState / 错误码；无 SQLState 的超时也继续记录为 UNKNOWN。

原生操作前先保存加密映射、APPLYING 成员及角色意图，核验登录和角色后标记 ACTIVE。失败保留 ERROR 成员与步骤，阻止查询；管理员重新提交可复用原账号和密码。原生同名账号必须能用保存的随机密码核验，不认领外部用户、不重置密码。源级锁和 revision 保证同时首授只产生一个账号。

移除成员会撤销平台角色；人工账号保留，托管账号只在核验所有权后删除。没有已验证 native_identity 的失败记录，需要再次验证保存的密码才能删除原生用户，避免误删外部同名账号。PostgreSQL 账号拥有业务表时删除明确失败并保留对象，平台不做 DROP OWNED / CASCADE。平台用户停用／删除仍只影响平台访问，销毁源内托管登录须先移除数据源成员。

Vue 成员页显示只读账号名及「平台自动创建」，托管账号不再提供手动绑定／换绑按钮。授权提示及移除确认同步区分托管和 DBA 账号。NEEDS_ACCOUNT 的历史成员重新保存角色分配即可进入自动创建流程。

## 验证结果

| 层次 | 结果 |
|---|---|
| Java 完整单元／服务／SQL／边界回归 | 615 项，零失败、错误、跳过（core 570、api 18、mcp-server 6、app 21） |
| Vue 完整 Vitest | 67 文件、481 项通过；权限中心 19 项 |
| Java / Vue 打包 | 四模块 Maven package 与 Vite 生产构建通过 |
| Python | 部署／离线包／Skill 契约 13 项通过，验收脚本 compileall 通过 |
| 真实 MySQL 8 | managed_accounts_smoke.py：20 项通过 |
| 真实 PostgreSQL 16.15 | managed_accounts_postgres_smoke.py：11 项通过 |
| 升级 | 本机已有 V57 库升级至 V58；已有账号全部保持非托管，新账号以密文保存；重启校验 58 份迁移通过 |
| 浏览器 | 管理员打开「本地演示 MySQL → 权限配置 → 成员」，演示成员账号显示「平台自动创建」和「正常」，无手工绑定入口 |

MySQL 覆盖 USER / SERVICE 首授即建号、独立原生登录查询、多角色复用同一账号和密文、撤权、禁止手工替换托管密码、真实 MCP 查询、操作记录脱敏、权限不足失败与显式重试、同名外部登录拒绝认领及拒绝删除、并发首授 200 / 409、旧 DBA 映射不变和托管账号移除。

PostgreSQL 覆盖 USER / SERVICE 自动 LOGIN、无超级用户／CREATEROLE／CREATEDB／复制／绕过 RLS 属性、角色继承、多角色复用、真实 MCP、管理账号缺 CREATEROLE 的指引及重试，以及成员创建真实业务表后移除失败且表保留。验收由测试 DBA 显式改回表所有权后，再次移除成功。临时表、账号、角色和源已清理，临时 PG 容器已移除。

Java 还锁定已有明文 DBA 密码不误作密文、托管密文失效不打开连接、密码不序列化、三引擎登录 DDL 转义、不使用 IF NOT EXISTS、错误脱敏不丢失未知结果语义。Doris 本轮只有 SQL 契约覆盖，无真实实例，不计为原生全流程通过。

## 复跑入口

```bash
# JDK17 / Maven；所有 frontend 命令前执行仓库 nvm use
mvn -B -ntp -f backend/pom.xml -Dtest='*Test,!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false package
npm --prefix frontend test
npm --prefix frontend run build

# 专用 Python venv 安装 backend/app/src/test/scripts/requirements.txt
python backend/app/src/test/scripts/managed_accounts_smoke.py \
  --credentials /private/credentials.json --backend-env /private/backend-env.json \
  --state /private/existing-service-smoke-state.json --key-file /private/runtime-env.json
python backend/app/src/test/scripts/managed_accounts_postgres_smoke.py \
  --credentials /private/credentials.json --backend-env /private/backend-env.json \
  --postgres-credentials /private/pg.json --key-file /private/runtime-env.json
```

MySQL 脚本使用 service_accounts_smoke.py --keep 创建的自有测试源，原有人工绑定 fixture 用于旧映射兼容断言；同一轮脚本创建的用户／角色／schema 最终清理。PG 配置字段为 host / port / user / password / dbname；runtime-env 含运行中后端的 ODW_CREDENTIAL_KEY，文件在仓库外、仅本人可读。密码不作为命令行参数或测试输出。

## 本机试用与部署边界

门户继续运行在 http://127.0.0.1:3000/，后端健康检查 http://127.0.0.1:8082/api/v1/health。保留「本地演示 MySQL」内的「自动建号演示」（平台用户名 auto_demo_1104），已自动创建并核验数据库登录，便于查看／调整角色；原有成员和数据源未被重建。试用新成员时直接选择用户／服务账号和角色，保存后即可查看生成账号。

必须保留 ODW_CREDENTIAL_KEY 并同步发布 Java / Vue。现有人工映射不改密；旧后端不能使用新托管密文，降级前应核对并处理托管账号／映射，不能仅回滚 jar。具体步骤见[运行说明](../handbook/backend-runtime.md)与[权限手册](../handbook/features/users-and-data-permissions.md)。

完整 backend/Dockerfile 构建两次在解析 maven / eclipse-temurin 基础镜像时遇到 Docker Hub 网络 DeadlineExceeded；registry-1.docker.io 和 auth.docker.io 的直接连接也超时，未将这项记为通过。当前 jar 已用先前成功构建的本机 Java17 运行镜像离线封装；容器启动、健康、管理员登录和V58托管成员响应均通过，临时容器已清理。这不替代完整 Dockerfile 构建。待网络恢复后需重跑标准镜像构建。Doris 真实自动建号／删除与生产管理账号权限、网络拓扑仍需部署环境验证，已登记[统一待办](../TODO.md)。

后续状态（2026-11-04）：网络恢复后正式 backend/Dockerfile 构建已成功，并完成当前代码的真实Nginx／TLS代理与权限评审回归；上述Docker网络阻塞记录为历史验证状态。见[评审修复报告](2026-11-04-backend-pr-review-fixes.md)。
