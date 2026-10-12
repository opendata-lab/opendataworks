# Vercel 构建升级至 Node 24

## 现状与问题

Demo 和文档站通过 `.github/workflows/vercel-*-deploy.yml` 执行 `vercel pull`、`vercel build --prod` 和预构建发布。两个 CI 作业固定 Node 20，项目设置也可能保留 `20.x`。Vercel 自 2026-10-01 停用 Node 20 新构建，当前报 `BUILD_UTILS_NODE_VERSION_DISCONTINUED`。

## 范围与方案

- 影响 Vue/Vite Demo、VitePress 文档站及其 GitHub Actions/Vercel 构建配置；Java、Python、DataAgent Pi、Docker 部署不变。
- 两个 Vercel workflow 使用 Node 24。
- 文档站 workflow 的路径触发器包含自身配置文件，使仅修改部署流程时也会重新构建。
- 仓库根新增仅含私有包标识和 `engines.node: "24.x"` 的 `package.json`，供根目录 Demo Vercel 项目解析版本。安装和构建继续由根 `vercel.json` 指向 `frontend/`。
- 文档站 `website/package.json` 声明相同 engine，并同步锁文件根元数据。
- 本地通用 `.nvmrc` 仍用于现有开发基线；Vercel 兼容性验证在成功加载该基线后显式 `nvm use 24`。这是已确认的托管平台版本差异，不扩展到其他 Node 子项目。

## 接口与取舍

无 API、数据模型或产物路径变化。`engines.node` 精确限定主版本，避免开放范围随 Vercel 默认版本变化；仅更新 CI 执行器不足以覆盖 `vercel pull` 获取的云端项目版本。根包不增加依赖、workspace 或安装步骤。

依据：[Vercel Node 20 停用公告](https://vercel.com/changelog/node-js-20-is-being-deprecated)，明确允许通过 package.json engines 覆盖项目设置。云端项目设置建议同步到 `24.x`；本次不直接修改远端或发布生产。
