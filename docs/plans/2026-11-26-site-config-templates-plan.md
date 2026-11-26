# 现场配置不随升级覆盖 计划

设计：[2026-11-26-site-config-templates-design.md](../design/2026-11-26-site-config-templates-design.md)

## 步骤

1. `git mv` 两个文件为 `.example`，`.gitignore` 加入 `deploy/docker/nginx/frontend.conf`、`deploy/docker/backend/application.yml`。
2. 新增 `scripts/lib/site-config.sh`（`ensure_site_configs`）；`start.sh`、`restart.sh` 引入并在 compose 之前调用。
3. `create-offline-package.sh`：tar 排除两个现场文件；校验包里只有 `.example`。
4. `frontend.conf.example` 删除 `/api/v1/nl2sql-admin/` 注释示例。
5. 文档：`deploy/README.md`、`docs/handbook/operations-guide.md`、`docs/handbook/authentication.md`。

## 验证

- `tests/test_deploy_config_contract.py`：路径改为模板；新增缺文件复制、已有文件不覆盖、打包排除三项。
- `bash -n` 检查改动的脚本。

## 上线与回退

- 已有部署：同步新包后现场文件保持不变，无需操作；新部署首次启动自动生成。
- 回退：用旧包同步会再次覆盖现场文件（旧行为），回退前先备份这两个文件。
