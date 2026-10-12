# Vercel Node 24 实施计划

设计：[Vercel 构建升级至 Node 24](../design/2026-10-12-vercel-node24-design.md)。

## 实施

1. 将 `.github/workflows/vercel-demo-deploy.yml` 和 `vercel-docs-deploy.yml` 的 setup-node 改为 24；文档站路径触发器包含 workflow 自身。
2. 创建根 `package.json` 的 Node 24 声明；更新 `website/package.json` 和 `website/package-lock.json` 根 engine。
3. 补充 `docs/handbook/vercel-deployment.md`，说明版本、项目根路径及重试步骤。
4. 成功 `nvm use` 后安装/切换到 Node 24，验证 Demo 与文档站构建。检查 JSON、版本一致性和 `git diff --check`。

## 发布与回退

合入后重跑两个 GitHub Actions 工作流，确认 `vercel build --prod` 和预构建发布成功。若云端仍读取旧配置，将对应项目 Settings → Build and Deployment → Node.js Version 设置为 `24.x` 并重新 pull/build。没有项目凭据时，只报告本地验证，不声称已验证生产部署。

回退代码不恢复 Vercel 对 Node 20 的支持；兼容性问题应在受支持的 Node 主版本上修复。已发布静态站可通过 Vercel 恢复上一份成功部署。

## 验证记录

- 2026-10-12：成功加载仓库基线 `nvm use`，然后 `nvm use 24`；实际 Node v24.21.0 / npm v11.19.0，复用已有依赖。
- `npm --prefix frontend run build:demo -- --outDir /tmp/odw-vercel-node24-demo-20261012` 通过（37.52s），产出 index.html；仍有 Sass legacy API 和 chunk size 提示。
- `npm --prefix website run build -- --outDir /tmp/odw-vercel-node24-docs-20261012` 通过（17.52s），完成页面渲染并产出 index.html。
- 根/website package engines、website 锁文件元数据、两条 CI Node 版本的一致性断言通过；`git diff --check` 通过；文档路径和交叉链接已检查。
- 本地未提供 `VERCEL_TOKEN`，未执行依赖项目认证的 `vercel pull/build` 或生产部署；云端项目配置读取及 GitHub Actions 完整部署链路仍待验证。
