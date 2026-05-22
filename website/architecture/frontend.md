# 前端架构

## 技术栈

- **Vue 3** — Composition API
- **Vite 5** — 构建与开发服务器
- **Vue Router 4** — 路由管理
- **Pinia** — 状态管理
- **Element Plus** — UI 组件库
- **ECharts** — 图表与血缘可视化
- **Vue Flow** — 流程图
- **CodeMirror** — SQL 编辑器
- **Axios** — HTTP 客户端

## 目录结构

```
frontend/src/
├── views/          # 页面组件
├── components/     # 公共组件
├── router/         # 路由配置
├── stores/         # Pinia 状态
├── api/            # API 请求封装
├── utils/          # 工具函数
└── assets/         # 静态资源
```

## 路由结构

| 路由 | 页面 | 说明 |
|------|------|------|
| `/dashboard` | Dashboard | 首页看板 |
| `/tables` | DataTable | 数据资产管理 |
| `/tasks` | DataTask | 任务管理 |
| `/lineage` | Lineage | 数据血缘 |
| `/monitor` | Monitor | 执行监控 |
| `/intelligent-query` | IntelligentQuery | 智能查询 |
| `/system` | System | 系统管理 |

## API 代理

开发环境通过 Vite proxy 转发请求：

- `/api/v1/nl2sql/*` → DataAgent Backend (:8900)
- `/api/v1/dataagent/*` → DataAgent Backend (:8900)
- `/api/*` → 主后端 (:8080)

## 状态管理

使用 Pinia 管理全局状态，按模块拆分 store：

- `useUserStore` — 用户信息
- `useAppStore` — 应用配置
- 各业务模块按需创建独立 store
