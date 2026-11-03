# 普通用户与服务账号验收

日期：2026-11-02。[设计11](../design/2026-10-26-dataagent-datasource-identity-design.md#service-accounts)、[计划7](../plans/2026-10-26-dataagent-datasource-identity-plan.md#7-账号类型与服务token实施2026-11-02)、[使用手册](../handbook/features/users-and-data-permissions.md)。

## 交付范围

平台账号分USER／SERVICE，类型不可变；人保留原登录流程，服务账号无密码／网页登录／平台管理员权限。Token单独管理、多凭据并存、默认90天且限1～365天、只存SHA256，创建响应仅一次返回明文并禁止缓存。启停／到期／撤销／软删除在下一次请求校验。

管理中心为「账号管理」，新增账号先选类型；服务账号有名称／用途和Token管理，没有密码／管理员选项。数据源成员选择本平台启用账号，角色与原生登录绑定关系不变；不读取DataAgent智能体目录，不接受外部助手ID作为权限主体。

MCP使用Authorization Bearer服务Token；每次HTTP请求由Java验证，Java仍要求独立内部转发密钥及私网门禁。外部agent_id／operator头不决定身份。无用户头按服务账号权限查询，有用户头严格按人和服务账号交集；未知人不回退。开发写接口本期403。Token时间显式UTC保存和比较、API带Z返回，页面按本地时区显示。

## 环境与恢复

本机Java8／8082，Vue3000，MCP8801，DataAgent8900，平台库odw_split_local_20261029，MySQL8.0.45。V57前已完整备份平台库，备份仅本机保存。V57实际执行成功，把原AGENT绑定迁为SERVICE数值ID，原生账号及已有授权未改变。后续UTC修复没有修改已执行的迁移；重启后Flyway校验58个迁移、当前版本57。

本次构建、服务重启及33361测试转发由外部运维步骤完成，测试脚本不启动或重启环境。MySQL测试库、原生账号、角色及源由验收脚本独立创建。凭据和临时状态在本机私有文件保存，不提交、不输出到报告或截图。

DataAgent现有portal种子记录仍为plugin，管理API明确拒绝修改。备份原记录后，本机运维将该条目转为普通configured HTTP MCP，保留server_id／URL／助手引用，仅更新Authorization服务账号凭据；随后通过标准PATCH API核验可编辑。没有修改独立DataAgent源码。保留必要本机集成Token90天，后续到期前通过账号管理轮换；内部hop密钥未替代服务Token。

## 自动验证

| 范围 | 结果 |
| --- | --- |
| Java定向测试与整套打包 | 原定向集合67项，失败0／错误0／跳过0：odw-auth23、agent-api7、backend37。随后Token时间修复定向5项，失败0／错误0／跳过0，含新增UTC契约检查；两次Maven打包成功。不是全仓Java测试。 |
| MCP Python全模块 | 44项通过，失败0，2项现有依赖警告；在独立Python3.13虚拟环境安装本模块requirements并验证依赖导入后执行。 |
| Vue相关组件／导航／API | 8文件42项通过，失败0；涵盖类型表单、禁止服务账号重置密码、Token一次展示／关闭清除／迟到响应／撤销确认换账号隔离／失败重试／停用、源内类型校验及无DataAgent调用。 |
| Vue生产构建 | Node20.19.0，nvm use成功后构建通过；保留现有体积／依赖警告，无构建错误。 |
| 真实MySQL／HTTP／MCP | 最终27项通过，失败0，脚本service_accounts_smoke.py。见下节。 |
| 既有MySQL／PG／故障脚本 | 更新USER／SERVICE与Bearer契约，5个相关脚本Python语法通过。此前PG／故障的真实结果见[上一轮报告](2026-11-02-users-and-permissions-verification.md)，本次没有重新建立真实PG／Doris环境，不混记为本次运行。 |

Java命令：

```sh
mvn -pl backend -am -Dmaven.compiler.useIncrementalCompilation=false -Dtest=ServiceAccountTokenServiceTest,PlatformUserManagementServiceTest,SysUserServiceTest,AuthenticationFilterTest,AgentApiAuthInterceptorTest,AgentApiConfigurationTest,AgentIdentityContractTest,AgentDatasourceAccessServiceTest,DatasourceSubjectAccountServiceTest,AgentMetadataControllerTest,AgentQueryControllerTest,NativePermissionAdapterTest,PermissionCredentialCipherTest,PasswordInvalidationSqlTest,MetadataCollectionAuthorizationTest -Dsurefire.failIfNoSpecifiedTests=false package
mvn -pl backend -am -Dmaven.compiler.useIncrementalCompilation=false -Dtest=ServiceAccountTokenServiceTest -Dsurefire.failIfNoSpecifiedTests=false package
```

Python与前端命令（私有路径参数按本机实际路径传入）：

```sh
/tmp/odw-service-mcp-venv/bin/python -m pytest -q odw-mcp-server/tests
python backend/src/test/scripts/service_accounts_smoke.py --credentials <private-credentials> --backend-env <private-running-backend-env> --state <private-state> --keep
# 修正时间契约后对保留夹具复跑同一组验证；脚本可独立从干净环境建立夹具
python backend/src/test/scripts/service_accounts_smoke.py --credentials <private-credentials> --backend-env <private-running-backend-env> --state <private-state> --keep --verify-only
export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use
npm --prefix frontend run test -- src/router/__tests__/navigation.spec.js src/views/__tests__/Layout.navigation.spec.js src/api/__tests__/usersAndPermissions.spec.js src/views/settings/__tests__/UserManagement.spec.js src/views/settings/__tests__/ServiceAccountTokens.spec.js src/views/settings/__tests__/DatasourcePermissionCenter.spec.js src/views/settings/__tests__/DataSourceManagement.spec.js src/views/settings/__tests__/ConfigurationManagement.spec.js
npm --prefix frontend run build
```

27项覆盖：独立USER／SERVICE数据库账号与角色、生效元数据、普通用户登录及原生查询、用户不能管理Token、SERVICE不能密码登录／设密码／提升管理员／改类型、人不能生成服务Token、成员类型／存在性／旧AGENT标识拒绝、SERVICE不能分配实际ACTIVE写角色、摘要存储／90天／no-store／UTC、多Token元数据列表、非法有效期及Token归属拒绝、无用户头查两库、一个连接多角色JOIN、无权库／写SQL拒绝、提供人时交集及无效人拒绝、伪造agent/operator不改变服务主体、最后使用时间更新、到期拒绝、独立撤销／兄弟Token保留、账号停用／禁签发、重启用不复活撤销Token、旧共享凭据拒绝、Java双凭据校验、开发写API403、Token不能用于网页API、缺源映射及删除账号拒绝。

登录失败沿用原接口HTTP200／业务code400，断言无会话Cookie，不误报HTTP401。最初两次脚本断言与此旧契约不符，修正断言后复跑；未为迎合测试更改登录接口。浏览器发现生命周期时间缺时区造成8小时偏移，已修UTC并复跑Token相关测试和全部27项。

## 真实模型

已迁移的源1服务账号独立Bearer查询xinghe_retail.orders，已完成订单12单、pay_amount合计668.90。DataAgent使用同一平台服务Token、现有门户用户local:admin提交真实任务task_88a52863371548fdaf763277，DeepSeek deepseek-v4-pro终态finished、无error；持久化事件确认odw_search_tables／odw_resolve_datasource／odw_query_readonly，最终答案12／668.90，用户和助手2条消息已落库。提供人的这一轮是本机未签名头的功能验证，不是可信自然人委托验收。

## 浏览器与清理

管理员页面已核对账号类型列表、新建类型切换、服务账号只显示名称／用途／状态、Token元数据和本地时间。Token明文生成、撤销和权限变更通过API与组件测试验证，未在浏览器创建或展示真实凭据。截图仅有不可用作认证的展示前缀；保存于本机output/permissions。

普通用户浏览器验收首轮发现Data Studio展开连接调用管理员专用POST /{id}/test被拒；按[设计11.5](../design/2026-10-26-dataagent-datasource-identity-design.md)改为useCatalogTree直接以当前用户绑定的原生账号读取GET /{id}/databases，管理连接测试保持admin。修复后临时普通用户在浏览器展开测试源、打开orders并执行查询返回2行（截图output/permissions/service-user-query.png）。随后清理脚本核验测试库／原生账号／角色／元数据／平台账号均已删除，模型会话404，33361临时转发已停止，业务源管理员查询仍为12／668.90。

最终复核（2026-11-02 11:06～11:09）：

| 范围 | 结果 |
| --- | --- |
| 真实HTTP目录激活 | 临时未绑定普通用户12项通过：可列源；GET /databases明确拒绝「未配置用户的数据源账号映射」，不回退管理账号；POST /test与GET /metadata-scope均403；查询拒绝；管理员目录、连接测试和12／668.90查询正常；临时账号已删除且不再列出。 |
| Vue全量单测 | 67文件472项，471通过；primaryKeyControls一项在全量并发下17.8秒超时，单独复跑5项全过，记入[TODO](../TODO.md)。复核时发现Layout.spec.js仍按旧导航断言（工作流／设置、旧菜单index、缺el-sub-menu桩），按信息架构改版更新后11项通过。 |
| Vue生产构建 | nvm use（Node20.19.0）后通过，仅既有体积警告。 |
| Java定向 | MetadataCollectionAuthorization4、DorisClusterService8、DorisMetadataSyncService14、DatasourceSubjectAccountService6、AgentDatasourceAccessService1，共33项，失败0／错误0。 |

## 限制及委托工具记录

可信人委托签名／统一登录仍延期；服务Token只证明服务主体，不能证明未签名用户头。开发写工具的服务授权单独设计，当前403；本次没有真实PG／Doris全链路，沿用已验证方言实现，不声称这些环境验收。以上记入[TODO](../TODO.md)。

上一轮前端由agy Gemini Argon实现，本轮三次委托均被无交互CLI自动拒绝文件写入，实际没有落盘修改；本轮前端由主会话完成。核对启动器确认上轮使用跳过权限确认参数、本轮限定目录规则未生效；auto模式不可用提示两轮都有，不是差异原因。CLI即使报告SUCCESS／退出0也未认定委托完成，以实际文件和测试为准。
