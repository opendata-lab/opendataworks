# 数据源账号映射

旧版库级授权模型已由 V54 主体账号映射替换，不再提供旧权限接口或旧表兼容。平台只登记人/智能体在每个数据源上的数据库账号，权限由 DBA 在数据库授权。

参见[部署说明](../deploy/README.md#数据源主体账号映射v54--alembic-20261026_000029)、[设计](../docs/design/2026-10-26-dataagent-datasource-identity-design.md)和[验证报告](../docs/reports/2026-10-26-dataagent-datasource-identity-verification.md)。
