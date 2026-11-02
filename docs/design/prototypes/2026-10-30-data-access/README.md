# 统一数据权限中心交互原型与官方界面对照

日期：2026-10-30。关联[设计第9节](../../2026-10-26-dataagent-datasource-identity-design.md#unified-access-design)、[实施计划](../../../plans/2026-10-26-dataagent-datasource-identity-plan.md#unified-access-plan)。本目录只含独立原型，不替换生产页面，不访问任何真实授权API。

本轮原型已按最新选择更新：**角色配置资源权限、成员多选角色、成员每源绑定一个数据库账号**。只读／读写／数据管理员是新建角色模板，不固定对应三个共享账号；没有独立“执行身份”入口或用户组管理。生产实现尚未开始。

## 打开与体验

- [交互原型](index.html)：角色权限矩阵、成员多角色分配、成员账号绑定、权限合并、模板创建、源配置、授权记录及官方参考。
- [原型与官方实际截图并排对照](comparison.html)：左侧可切换17张本地原型图，右侧可切换Metabase和华为的官方原图；附DataWorks文档差异。
- 可直接用浏览器打开本地HTML。参考图通过官方URL在线加载，需要网络；未把远端图片下载进仓库。
- 也可在仓库根目录运行 `python3 -m http.server 5188 --bind 127.0.0.1 --directory docs/design/prototypes/2026-10-30-data-access`，打开 `http://127.0.0.1:5188/comparison.html`。只监听本机，和3000开发前端独立。

## 当前交互

本页为Operate型企业管理界面，默认先看成员；保持现有白底、蓝色主操作和顶部导航。数据源在统一页头选择，在成员／角色／操作记录间保持上下文。

1. **分配角色**：在成员行点击“分配角色”，勾选后一次保存；取消不落地。角色下面显示资源与业务权限摘要，不展示原生角色ID。
2. **绑定账号**：点击账号或“绑定账号”，独立小表单编辑。首次绑定／换账号需演示密码，原账号密码留空保留；错误在当前表单内显示。
3. **用户管理**：侧栏进入用户管理，新建平台用户时填写用户名、姓名、初始登录密码；不自动授予数据权限。编辑可修改姓名和启用状态。
4. **授权用户**：数据源列表点击“授权用户”，选择已有用户，或在原处新建用户并授权。没有默认人选，不包含数据库密码；授权后未绑定数据库账号的用户显示待绑定。
5. **查看权限**：行内“权限”查看角色合并结果，以业务动作和角色来源为主；原生权限明细按需展开。Doris示例中张明一个账号承接三个库的角色。
6. **配置角色**：角色页选择资源与动作，变更后才出现“保存权限”；原生细分、角色映射和权限说明按需打开。点击“管理成员”进入按当前角色筛选的成员列表，不再保留重复的内部成员页签。
7. **创建角色**：小表单填写名称、只读／读写／数据管理员模板与资源范围；生成草稿，确认保存后才生效。PG表／视图模板包含Schema USAGE前提，数据库不支持的独立授权不伪造。

2026-10-30补齐平台用户管理原型，明确新增用户与数据源授权的区别；平台登录密码不作为数据库密码。

主列表去掉重复副标题、教学区块、头像和逐行相同的账号说明，操作表单不再包含页签／“下一步”。数据源配置仍由侧栏进入；官方参考保留在原型说明入口，不占日常操作区。

本原型只修改内存示例，刷新重置。账号密码不保存／传输，连接和授权成功均为演示；不代表真实数据库GRANT、登录验证、跨库JOIN或DataAgent已验收。原型演示结果在权限确认页的“原型演示选项”中展开。

## 原型图

1. [角色与资源权限](01-role-permissions.jpg)
2. [授权预览](02-grant-preview.jpg)
3. [部分成功与步骤](03-partial-result.jpg)
4. [采集与管理连接](04-source-permissions.jpg)
5. [Doris统一动作与原生映射](05-doris-actions.jpg)
6. [Doris数据库级授权](06-doris-schema.jpg)
7. [PostgreSQL Schema授权](07-pg-schema.jpg)
8. [PostgreSQL视图授权](08-pg-views.jpg)
9. [成员管理](09-member-management.jpg)
10. [成员多角色分配](10-member-roles.jpg)
11. [成员数据库账号](11-member-account.jpg)
12. [新建角色模板](12-role-template.jpg)
13. [跨库角色权限合并](13-effective-permissions.jpg)
14. [用户管理](14-user-management.jpg)
15. [新增平台用户](15-create-user.jpg)
16. [已有用户授权](16-authorize-user.jpg)
17. [新建用户并授权](17-create-and-authorize.jpg)

`frames.html`是1440×960的出图画板，用iframe固定原型尺寸，不改变用户浏览器视口设置。JPEG为浏览器实际渲染截图，不是生成式图片。

## 官方截图来源与区别

| 产品与图 | 官方来源／原图 | 本次借鉴与区别 |
| --- | --- | --- |
| Metabase表权限 | [Learn教程](https://www.metabase.com/learn/metabase-basics/administration/permissions/data-permissions)／[官方WebP原图](https://cdn.metabase.com/learn/images/guide-to-data-permissions/grant-table-access.webp) | 借鉴按组／源配置与表级矩阵；它区分产品权限，我们按原生动作授权并使用成员绑定的受限账号 |
| 华为DataArts Studio数据视角，图6 | [配置角色](https://support.huaweicloud.com/usermanual-dataartsstudio/dataartsstudio_01_1157.html)／[官方PNG原图](https://support.huaweicloud.com/usermanual-dataartsstudio/zh-cn_image_0000001937488158.png) | 借鉴角色上下文与资源动作配置；我们不用独立权限同步按钮和后台追平队列 |
| 华为DataArts Studio角色详情，图1 | [配置角色](https://support.huaweicloud.com/usermanual-dataartsstudio/dataartsstudio_01_1157.html)／[官方PNG原图](https://support.huaweicloud.com/usermanual-dataartsstudio/zh-cn_image_0000002547029585.png) | 借鉴左角色、右详情及成员页签；我们不引入多层权限集和从属角色 |
| 阿里DataWorks数据访问控制 | [当前官方说明](https://help.aliyun.com/zh/dataworks/user-guide/data-access-control/)／[MaxCompute说明](https://help.aliyun.com/zh/dataworks/user-guide/manage-permissions-on-maxcompute) | 借鉴原生引擎差异、主动授权和记录职责；本次页面只作文字对照，不把流程图或重绘图称为实际控制台截图 |

资料核对：2026-10-30。华为页面标注更新2026-10-13；Metabase图来自当前官方Learn教程，未宣称对应某个精确软件版本或用户提供的固定提交。以上均为官方发布截图，非本次登录厂商租户控制台实拍；URL内容可能后续更新。DataArts角色管理文档的引擎范围有约束，不能据图声称其通用支持任意PG/MySQL/Doris。

## 验证范围

Node20.19.0脚本语法检查；浏览器验证成员分配／取消、独立账号绑定与空密码保留、新建平台用户／就地创建并授权／已有用户授权／重复用户名与必填错误／取消／停用联动、角色／账号筛选与空状态、跨源上下文、Agent写角色禁选、模板草稿、权限成功／失败／部分成功、角色到已筛选成员跳转，以及Escape关闭。检查1440、1024、760宽画板；窄表横向滚动，不挤压表单操作。截图由浏览器导出，官方图仍引用来源URL。

机械检查发现旧式有色投影提示，已改为中性投影。仅改独立原型和说明，未改Vue／Java／Flyway或执行真实授权；本轮未运行后端或生产应用构建。原生角色激活／撤权及真实SQL／Agent全链路仍待生产实现后验收。
