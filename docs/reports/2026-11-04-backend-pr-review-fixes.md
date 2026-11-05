# PR #493 评审修复与验证

- 日期：2026-11-04（本机 America/Los_Angeles）
- 评审基线：`19a02dcf`，用户粘贴的开放 PR 评审。
- 设计：[收敛设计评审补充](../design/2026-11-03-backend-mcp-auth-consolidation-design.md#pr评审后契约修正2026-11-04)
- 计划：[评审修复执行](../plans/2026-11-03-backend-mcp-auth-consolidation-plan.md#pr评审修复执行2026-11-04)

## 修复结果

| 评审问题 | 当前行为与回归 |
|---|---|
| 任务 input/output 半边血缘全部丢失 | 至少一个原始端点 ID 非空，每个非空 ID 对应的表都必须存在且可见；保留半边，隐藏表／缺失非空 ID 仍过滤。列表与导出共用此约束，覆盖两种半边、隐藏端点、悬空 ID 和空边。 |
| 导出限额不一致 | 表／数据源／血缘超过5000行均明确报错，要求缩小范围。评审称表导出设置 truncated，实际代码没有该标记，原行为是静默截断；本次移除静默丢行，不增加响应包装或兼容分支。 |
| MCP 矩阵参数绕过 SERVICE 分支 | HTTP 过滤器先解码并拒绝分号、反斜线、重复斜线、`.`／`..` 路径段与无效编码，再按解码路径分类；编码工具路径也不能走 USER Cookie。真实登录用户请求矩阵路径返回400。 |
| 门户 Nginx 把公网 MCP 转成桥接私网来源 | 门户精确 `/api/mcp` 与 `^~ /api/mcp/` 返回404，包含会匹配静态扩展的子路径。MCP 经独立受限后端入口，真实 Nginx HTTP／HTTPS 均验证拒绝。 |
| 前置 TLS 同源登录失败 | 部署与手册明确前置 TLS 必须显式配置浏览器 HTTPS Origin；不隐式信任 X-Forwarded-Proto。真实 TLS终止 → HTTP门户 → HTTP后端登录通过。 |
| MCP 省略 arguments、业务 owner 被覆盖 | null arguments 归一空对象后仍严格校验；三种无必填参数工具可调用，必填仍拒绝。表 owner 保留显式值，仅省略／空白时默认调用者；任务新建仅缺 owner 时默认，更新保留稀疏更新语义。 |
| 已有成员部分 GRANT 成功却可删除新角色 | 原生 I/O 前在平台事务保存旧／目标角色并集和 APPLYING。旧集合在修改前快照，成功替换为精确目标；PARTIAL／UNKNOWN 保留保护引用并标 ERROR。明确无成功步骤的 FAILED 恢复旧集合，仅旧 ACTIVE 可恢复 ACTIVE；旧 APPLYING 不恢复执行中。已有未映射成员自动建号也保护旧角色。真实 MySQL 用 ROLE_ADMIN + mysql.* SELECT 的受限管理账号重现 GRANT 成功、SET DEFAULT ROLE 失败，新角色原生存在且平台禁止删除，恢复 DBA 后重试成功。 |
| 移除／首次授权／改角色失败 UI 保持绿色 | USER、SERVICE、新建用户后续授权、角色调整、账号绑定与移除失败均刷新当前源成员及授权记录，保留原错误；更新重试目标 revision，隔离切源后的迟到响应。APPLYING 显示“执行中”，只有 ACTIVE 且配置完整才显示“正常”。Vue覆盖部分成功、移除失败、两种首授、新用户两步失败、状态筛选及旧请求隔离。 |
| 成员 CONNECT 失败错误指向管理连接 | 按实际失败连接区分成员权限指引与「授权管理连接」指引；保留数据库细节与操作状态。覆盖 MySQL1044、PG42501等管理错误，以及成员失败和真实管理激活失败。 |
| 委托查询审计与数据源解析回归 | EXPLAIN返回解析后的USER id与检查账号，查询审计同时保留SERVICE执行主体／数据库账号；数据源解析校验可选委托人的有效身份与源映射。真实MCP查询、平台审计行以及未知／未映射委托人拒绝通过。 |
| 创建登录超时、平台列标签及文档残留 | 局部秘密SQL Statement在执行前设置20秒超时，保留密码脱敏与UNKNOWN语义。平台Map同时支持大写snake_case和已归一camelCase。修复Skill预览Token／description错字、Schema描述旧命名、部署SERVICE只读限定等残留，并补契约测试。 |

## 最终验证

| 验证 | 结果 |
|---|---|
| Java四模块 | 633项，零失败／错误／跳过（core583、api18、mcp-server8、app24）；`package` 成功，含既有本地Spring生命周期用例 |
| Vue | 完整67文件487项通过；权限中心25项；Vite生产构建通过 |
| Python | 部署／离线包／Skill契约14项通过，验收脚本compileall通过 |
| 评审真实HTTP／MySQL | `pr_review_smoke.py` 19项通过 |
| 自动建号真实MySQL | `managed_accounts_smoke.py` 20项通过 |
| 自动建号真实PG16.15 | `managed_accounts_postgres_smoke.py` 11项通过，含对象所有权阻止删除与恢复 |
| 正式Dockerfile | `docker build -f backend/Dockerfile -t odw-backend:pr-review .` 成功；容器启动、58份Flyway校验、健康与登录通过 |
| 生产Nginx与TLS代理 | Nginx1.30.3-alpine挂载仓库正式配置，`nginx -t`通过；`portal_proxy_smoke.py` 15项通过（生产静态资源、健康、HTTP／HTTPS登录、MCP拒绝、矩阵路径拒绝、独立后端入口仍需服务凭据） |
| 浏览器 | 本地3000页面登录，打开「本地演示 MySQL → 权限配置」，自动建号演示仍显示“平台自动创建／正常”，未暴露手工绑定入口 |

共65项真实验收检查。测试源的授权管理连接在finally恢复DBA；临时角色、成员、登录、Schema和PG数据源均清理。TLS使用仅本机受信的一日自签证书，验证真实TLS终止后HTTP上游的Origin配置，不关闭证书校验。

Maven使用本机专用JDK17；所有frontend命令先nvm use到20.19.0；Python使用已安装脚本requirements的独立3.12虚拟环境。完整Java日志 `/tmp/odw-review-java-full.log`；Vue `/tmp/odw-review-vue-full.log`、`/tmp/odw-review-vue-build.log`；Python `/tmp/odw-review-python.log`；四组真实验收见 `/tmp/odw-review-{acceptance,managed-mysql,managed-pg,proxy-acceptance}.log`；Docker构建 `/tmp/odw-review-docker-build.log`。私有凭据和测试状态文件未提交。

## 复跑与运行状态

```bash
mvn -B -ntp -f backend/pom.xml -Dtest='*Test,!*IntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false package

export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh"
nvm use
npm --prefix frontend test -- --run
npm --prefix frontend run build

python -m pytest -q tests odw-skills/tests
python backend/app/src/test/scripts/pr_review_smoke.py --help
python backend/app/src/test/scripts/portal_proxy_smoke.py --help
```

真实脚本依赖已准备的本地受控源与私有输入，详见各脚本说明；容器、TLS证书和运行环境由外部准备，脚本不负责构建或重启。测试后只清理本轮隔离代理／后端／PG容器，保留原生后端8082、前端3000、MySQL与Dolphin，以及原有演示成员。

此前Docker Hub超时已在本轮网络恢复后通过正式构建解决。Doris仍无实库，独立DataAgent模型最终消息及生产快照升级没有在本轮重跑；沿用[统一待办](../TODO.md)中的外部环境验收，不将其计为通过。Sass弃用与Vite大chunk提示为既有构建警告。
