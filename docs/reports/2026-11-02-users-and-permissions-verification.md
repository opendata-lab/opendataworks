# 用户与多角色权限实现验收

实施：2026-11-01～02。依据[设计第10节](../design/2026-10-26-dataagent-datasource-identity-design.md#implementation-contract)、[计划第4节](../plans/2026-10-26-dataagent-datasource-identity-plan.md#unified-access-plan)。前端由用户指定的agy Gemini Argon实现，主任务审查、补充错误状态和页面定位，后端／数据库／集成验证由主任务完成。未提交Git commit。

## 实现

- 用户管理管理员API和页面：创建、编辑、启停、删除、改密、实时角色和会话版本校验；BCrypt、最后管理员／自身保护、操作审计。
- 每个源成员优先：一个成员账号持多个平台角色，每个角色映射无登录原生ROLE；只读／读写／管理员为创建模板。支持数据库／Schema、表、视图，公共动作保留原生边界。
- 预览／确认／即时GRANT与REVOKE／回读／平台短事务，操作步骤审计、并发锁和版本冲突；部分失败只保护相关角色／成员。无需后台权限同步任务。
- 专用管理连接AES-GCM密文保存；查询账号沿用唯一datasource_subject_account，不回退到管理账号。旧账号写API也经新服务保证撤权和Agent只读约束；POST旧接口现返回成员结果，字段以新权限API为准。
- Flyway V55、V56已在本地平台库实际迁移，原服务和现有业务源保留。
- 补齐既有数据源配置CRUD、设默认和管理连接测试的管理员限制；普通用户源列表可读，配置写入口隐藏，不能通过改连接绕过新角色中心。

## 自动检查

最后并发修复后Java定向测试53项通过（auth31、backend22），Maven package成功：

```sh
mvn -pl backend -am -Dmaven.compiler.useIncrementalCompilation=false -Dtest=SysUserServiceTest,AuthenticationFilterTest,Hs256JwtServiceTest,AuthControllerAgentUserIdTest,DatasourceSubjectAccountServiceTest,PlatformUserManagementServiceTest,NativePermissionAdapterTest,PermissionCredentialCipherTest,PasswordInvalidationSqlTest,MetadataCollectionAuthorizationTest -Dsurefire.failIfNoSpecifiedTests=false package
```

前端Node20.19.0（先nvm use），7个测试文件32项通过：navigation、Layout.navigation、usersAndPermissions、UserManagement、DatasourcePermissionCenter、DataSourceManagement、ConfigurationManagement；生产build成功。包含非SUCCESS删除不报成功、改密UTF-8边界、角色定位已授权库和刷新后对象保留等回归。已有测试替身缺router／pinia注入警告与构建chunk体积提示，不影响退出成功。Impeccable对三个Vue源文件的正则机械检测无命中；该检查不包含DOM低对比度等规则，另以真实浏览器截图核对布局。

## 真实本地链路

平台8082／Vue3000／MCP8801／DataAgent8900，平台测试库odw_split_local_20261029。脚本只使用外部准备好的运行环境，不安装或重启运行时。凭据和临时状态以600权限保存在本机/tmp，不入库、不写报告。MySQL复用本机8.0.45的隔离库和账号，通过专属33361转发端点登记测试源；PostgreSQL使用独立16容器55443，未更动已有PG实例。

| 验收 | 实际结果 |
| --- | --- |
| MySQL平台HTTP／原生权限 | 47项通过：预览无副作用、GRANT回读、重复请求不重放、版本冲突、用户CRUD与登录、普通用户管理403、缺映射拒绝、一个账号多角色跨库JOIN、库级未来表、角色资源／成员撤权、真实INSERT UPDATE DELETE、两用户范围不同、启停／改密旧会话失效、角色升降立即生效、自身保护、Agent写角色拒绝、元数据采集。 |
| MySQL DDL与视图 | 另7项通过：角色CREATE／CREATE VIEW／ALTER／DROP／SHOW VIEW下发和回读，实际建表改表、建／删视图、查看定义、删表，移除角色后建表拒绝；业务管理员模板不能GRANT转授权。 |
| 跨源并发保存 | 真实并行首次复跑发现平台RR空范围DELETE／INSERT死锁，正确报PARTIAL。权限短事务改READ_COMMITTED后，新预览明确修复旧结果；局部BEFORE INSERT测试触发器仅延迟两个测试ROLE，使两源平台事务强制重叠，两边SUCCESS，平台版本和原生新预览均一致（无剩余SQL）。不改全局隔离级别，不自动重放原生SQL。触发器finally清除。 |
| 数据源配置边界 | 5类真实HTTP配置写请求均403；旧账号POST保存仍经新角色路径，验证保留成员原有多角色。 |
| PostgreSQL原生权限 | 最新完整复跑20项通过：NOLOGIN角色和多角色继承、跨Schema JOIN、未授权拒绝、Schema当前对象范围、未来表拒绝、ALTER不映射GRANT、SERIAL序列USAGE写入、移除写角色保留读取、仅视图用户可读视图且底表拒绝、超级账号不能绑定Agent、只读Agent绑定、撤权及采集；追加CREATE撤权后新建拒绝、已创建对象的所有权保留。 |
| 双写故障与账号变更 | 10项通过：受限管理账号CREATE ROLE成功但GRANT失败→PARTIAL；重复请求不重放；其他角色可查询；恢复管理连接后新预览修复。原生SQL成功后通过局部测试触发器注入平台提交失败→PARTIAL；关联成员受保护，另一用户可查；移除触发器后重新预览恢复。DBA改掉成员密码后仍成功撤销原生角色；可重新绑定。触发器与受限管理账号均在finally清理。 |
| Portal MCP | MySQL同一Agent下用户1可查A/B，用户2可查A、B拒绝；PG用户1可查A/B，用户2仅视图成功、底表拒绝，各4项。真实调用odw_query_readonly，非模拟工具。 |
| 真实模型 | DeepSeek deepseek-v4-pro，任务task_81cb3e57c6ae45db9f7f706c终态finished，无error；真实调用odw_get_table_ddl／odw_search_tables／odw_query_readonly。隔离订单表结果2单、30，与SQL一致；用户和助手2条消息已持久化。 |

脚本位于backend/src/test/scripts：data_permissions_smoke.py、postgresql_permissions_smoke.py、permission_failure_smoke.py、permission_concurrency_smoke.py、permission_ddl_smoke.py、permissions_mcp_smoke.py。前两个提供--keep和--cleanup；失败注入只允许明确的本地平台测试库。需使用各模块依赖齐备的Python3.10+虚拟环境。MCP脚本使用DataAgent现有MCP服务配置，凭据不输出；测试头为显式测试用户，不是应用默认身份。

## 浏览器

管理员实际登录后可看到用户表、新增表单、源内成员多角色、账号与实际权限入口、角色数据库／表／视图矩阵和当前引擎原生说明；角色默认定位到已有授权，Agent角色写动作禁用。普通用户实际登录成功，导航隐藏用户管理及源配置写入口，展开源仅见已授权A／B；点击orders并执行预置SELECT，页面成功显示两行(1,10)、(2,20)。智能问数页面以该用户加载真实历史会话，工具过程和2／30.00聚合结果完整显示。随后退出临时用户并恢复管理员。截图保存在本机output/permissions，截图API额外白边按真实1278×800视口裁切，未修改页面内容。

## 已知边界

- MySQL8.0.45／partial_revokes=OFF的含_／%库名ROLE授权存在原生Bug110582。实际验证未转义扩大到近似库名，转义后部分库级访问失败；生产预览明确拒绝该组合。精确表级访问成功且近似库拒绝。未修改全局配置，未用当前表替代未来对象语义；修复版本／partial_revokes=ON未验收。
- PG新增只读检查实测发现SQL优化器可先对索引调用has_sequence_privilege，已用按relkind的CASE限制函数求值，再经真实PG Agent绑定和MCP验证。
- 本机无真实Doris，Podman现有VM2GB，不为本次测试修改全局VM或影响已有服务。Doris仅SQL方言契约检查，原生GRANT／LOAD与视图／EXPLAIN回读须内网验证，不宣称Doris全链路验收。
- 签名用户身份、逐AgentToken和用户委托按用户要求延期；现有身份头仍可被不可信客户端伪造，本报告不构成生产可信多用户身份验收。
- 平台停用／删除用户不删除原生账号；已有直接授权和外部角色可能扩大范围。跨源同物理账号未去重，单源内禁止其他成员复用账号。
- 发现既有数据源软删除未释放host/port唯一键，影响相同端点重建，已记TODO。本轮测试源清理前只改其自有坐标到removed-*.invalid，再软删除，不修改业务源。

## 清理与交付

本次临时模型会话已删除并确认404；两批临时平台测试用户已软删除，测试源已软删除，成员／角色／账号映射已通过管理API清理，原生MySQL／PG测试账号、ROLE和Schema逐一查询确认不存在。两批测试表元数据经API软删除，其测试字段与管理连接密文记录仅按自有源ID定向清理；授权／查询审计保留。专属PG容器已移除，33361转发已停止；原平台、前端、DataAgent、MCP及已有容器保留。清理后管理员在原业务源1查询已完成订单仍为12／668.90。

本地管理加密密钥已持久化到忽略提交的deploy/.env（权限600），服务使用相同密钥；报告与日志不包含其值。密钥与部署说明随实现更新。截图output/permissions/members-20261102.png、role-20261102.png、query-20261102.png、studio-20261102.png为清理前真实验收画面，非预置演示数据。未提交Git commit、未作生产发布。
