# 成员实际权限回读修复验收

日期：2026-11-02。对应[设计13](../design/2026-10-26-dataagent-datasource-identity-design.md#13-成员原生权限回读修复2026-11-02)、[计划9](../plans/2026-10-26-dataagent-datasource-identity-plan.md#9-修通实际权限回读2026-11-02)及PR #491。

## 问题与修复

演示MySQL源1的USER和SERVICE账号都能连接数据库；旧inspect却在校验成员身份后，使用未单独配置管理凭据的数据源连接读取mysql.role_edges。采集账号缺少此系统表的SELECT权限，MySQL返回1142。旧异常提示将此阶段与成员连接失败混在一起。

现改为：管理员请求 → 成员绑定账号连接 → 确认数据库实际user/host → 同一连接读取自身原生授权。MySQL读取information_schema.ENABLED_ROLES，以实际role name/host构造SHOW GRANTS FOR CURRENT_USER USING，展开新连接激活角色的权限；保留外部角色host与引号转义。未激活角色不按生效权限展开，不执行SET ROLE ALL，不打开管理连接，也不提升采集账号的权限。Doris使用自身SHOW GRANTS；PG保留原有原生目录查询及输出格式，均在验证过的成员连接读取。

连接、身份验证、权限读取失败时反馈步骤、SQLState和原生错误码；日志只记录数据源／主体／步骤及代码，不记录密码、连接串、原生授权内容或原始异常消息。响应字段和前端接口保持，授权写入仍使用独立管理连接。

依据：[MySQL SHOW GRANTS](https://dev.mysql.com/doc/refman/8.0/en/show-grants.html)、[ENABLED_ROLES](https://dev.mysql.com/doc/refman/8.0/en/information-schema-enabled-roles-table.html)、[Doris SHOW GRANTS](https://doris.apache.org/docs/dev/sql-manual/sql-statements/account-management/SHOW-GRANTS/)。

## 自动验证

Java8定向21项，失败0／错误0／跳过0，Maven打包成功：

| 测试 | 项数 |
| --- | --- |
| DatasourcePermissionInspectionTest | 6（含USER／SERVICE参数化） |
| NativePermissionInspectionTest | 3 |
| NativePermissionAdapterTest | 4 |
| PermissionCredentialCipherTest | 2 |
| DatasourceSubjectAccountServiceTest | 6 |

新增用例覆盖无需管理连接／系统表、SERVICE只读连接标识、无激活角色、多个激活角色与非%host／引号转义、Doris自身授权及Password字段清除、连接／读取失败代码、关闭连接、实际身份不匹配和缺少绑定。不是全仓Java测试。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_361.jdk/Contents/Home mvn -pl backend -am -Dmaven.compiler.useIncrementalCompilation=false -Dtest=NativePermissionAdapterTest,NativePermissionInspectionTest,DatasourcePermissionInspectionTest,PermissionCredentialCipherTest,DatasourceSubjectAccountServiceTest -Dsurefire.failIfNoSpecifiedTests=false package
```

最初默认Java构建与显式Java8构建短暂重叠，前者已中止；以上结果以显式Java8的完整成功记录为准。没有修改Maven依赖或编译配置。

## 真实MySQL／HTTP

在已启动的MySQL8.0.45、Java8082与平台库odw_split_local_20261029验证。构建后由测试外部运维步骤重启后端，保留平台库、认证配置和凭据密钥；等待管理员真实登录成功后完整运行。测试脚本不启动或重启服务，凭据由本机私有JSON读取，不写入仓库或日志。

`permission_inspection_smoke.py`最终17项通过，失败0：

- 既有SERVICE及USER分别回读真实原生授权。
- 临时账号直接表授权、默认角色授权和嵌套角色授权均返回；非%角色host保持，未激活角色权限未被计为当前有效。
- 回读身份与CURRENT_USER一致；API每条授权与同一账号直接SHOW GRANTS输出一致。
- 三张分别通过直接／默认角色／嵌套角色授权的表可实际SELECT；未激活角色对应的表被MySQL1142拒绝。
- 临时成员无mysql.role_edges读取权限（1142），仍可完成实际权限读取。
- 删除临时数据库登录账号后，接口明确报“连接成员数据库账号失败”及1045，不回退采集／管理账号，不返回空成功。
- 临时绑定／平台账号及原生账号／角色／库已清理；现有源成员、角色与管理连接配置和测试前一致，额外执行一次cleanup通过。

ENABLED_ROLES实测返回激活的顶层角色；嵌套角色通过SHOW GRANTS USING递归展开。初版脚本错误要求子角色也单列在ENABLED_ROLES中，该断言已改为核对默认角色host、未激活角色和实际继承查询，再完整复跑17项通过，没有为迎合该断言调整生产实现。

```sh
# 私有配置字段沿用data_permissions_smoke.py；选取无托管角色的既有MySQL源。
<python-venv>/bin/python backend/src/test/scripts/permission_inspection_smoke.py --credentials <private-credentials.json> --state <private-state.json> --source-id 1
<python-venv>/bin/python backend/src/test/scripts/permission_inspection_smoke.py --credentials <private-credentials.json> --state <private-state.json> --source-id 1 --cleanup
```

脚本使用Python3.13专用虚拟环境，先验证httpx／pymysql可导入；语法检查通过。清理后再次运行会生成新名称，避免复用平台软删除用户名；中途失败可按私有state执行幂等cleanup。

## 浏览器与范围

管理员现有会话验证：源内仅保留原两位成员，服务账号和管理员的“实际权限”弹窗均展示真实GRANT结果，服务账号显示xinghe_retail的SELECT授权；无旧错误提示。截图保留本机output/permissions/native-permission-inspection-fixed.png，不含凭据。未在浏览器输入或提交密码，也未修改业务账号授权。

本轮没有前端代码、迁移或部署配置变化，不重复跑前端构建／单测及MCP／模型；前端上一轮验证见[拆分报告](2026-11-02-permission-center-component-extraction.md)。本轮没有真实PG／Doris实例验收，既有对应环境待办保留，不将MySQL成功视作全引擎实测。

## 交付与回退

将演示源回读失败待办移入已完成，更新手册和原报告的后续说明，提交推送现有PR #491。回退只需还原本次Java代码并部署旧Jar，无数据库迁移或授权恢复；临时测试资源已清理。
