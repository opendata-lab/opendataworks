# Vercel Demo 与文档站部署

两个 GitHub Actions 工作流使用 Node 24 和 Vercel CLI，顺序为拉取生产项目配置、本地生产构建、发布预构建产物。

| 项目 | Vercel Root Directory | 版本声明 | 构建产物 |
| --- | --- | --- | --- |
| Demo | 仓库根目录 | 根 `package.json` 的 `engines.node: "24.x"` | `frontend/dist` |
| 文档站 | `website` | `website/package.json` 的 `engines.node: "24.x"` | `.vitepress/dist` |

Vercel 已于 2026-10-01 停用 Node 20 的新构建。CI 的 setup-node 和 Vercel 项目运行时是两项配置；`engines.node` 覆盖项目版本，云端 Settings → Build and Deployment → Node.js Version 也建议设置为 `24.x`。参见 [官方公告](https://vercel.com/changelog/node-js-20-is-being-deprecated)。

修改合入后重跑对应工作流即可。手动重试应先切换到 Node 24，然后在与 CI 相同的项目上下文中执行：

```bash
vercel pull --yes --environment=production --token="${VERCEL_TOKEN}"
vercel build --prod --token="${VERCEL_TOKEN}"
```

若仍报 `BUILD_UTILS_NODE_VERSION_DISCONTINUED`，检查是否链接了正确的项目、Root Directory 是否与上表相符，以及 pull 后使用的版本设置。`Build not running on Vercel` 是本地构建提示；本次失败来自停用的 Node 版本。

本地兼容性验证：从仓库根加载 nvm 并成功 `nvm use`，首次需要时 `nvm install 24`，随后 `nvm use 24`，再执行 `npm --prefix frontend run build:demo` 和 `npm --prefix website run build`。通用开发仍遵循仓库 `.nvmrc`；DataAgent Pi 使用自己的 Node 配置。
