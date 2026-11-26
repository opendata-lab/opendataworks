# 现场配置不随升级覆盖 设计

## 现状与问题

`deploy/docker/nginx/frontend.conf`（门户 nginx，含 DataAgent 转发）和 `deploy/docker/backend/application.yml`（OAuth 提供方）由仓库跟踪，并原样打进离线包。现场按内网地址、IdP 改过之后，把新离线包同步到部署目录就会被包里的版本覆盖：DataAgent 转发失效，`application.yml` 回到全注释即 SSO 登录失效。只有 `deploy/.env` 不受影响，因为仓库和离线包只带 `.env.example`。（2026-11-26 上线时现场发生。）

## 方案

两个文件按 `.env` 的方式处理：

- 仓库只跟踪 `frontend.conf.example`、`application.yml.example`；`frontend.conf`、`application.yml` 加入 `.gitignore`。
- `scripts/lib/site-config.sh` 的 `ensure_site_configs` 在缺文件时从 `.example` 复制，已有文件不动。`scripts/start.sh`、`scripts/restart.sh` 在 compose 之前调用；离线包的 `load-package-and-start.sh` 经由 `start.sh`。缺文件时不能交给 compose：绑定挂载的源文件不存在会被 Docker 建成目录。
- `scripts/create-offline-package.sh` 排除两个现场文件，只打包 `.example`，并在包里出现现场文件或缺模板时失败。
- 顺带：`frontend.conf.example` 删掉门户用不到的 `/api/v1/nl2sql-admin/` 注释示例（widget 只用 `/dataagent/widget/`、`/api/v1/dataagent/`、`/api/v1/nl2sql/`）。

## 取舍

- 模板之后的改动（例如新增的安全规则）不会自动进入现场，需要运维对照升级说明手工合并，与 `.env.example` 相同。换来的是升级不再静默改掉现场配置。
- 以 git 检出方式部署且改过这两个文件的服务器，`git pull` 会因本地修改而拒绝更新，需要先把文件另存、拉取后放回；未改过的会被删除，下次 `start.sh` 从模板重建。

## 影响

只涉及部署脚本、模板与文档，不改镜像和应用代码。
