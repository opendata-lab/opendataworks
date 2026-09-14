# Python 包镜像源配置设计

**Date:** 2026-09-14
**Goal:** 让 OpenDataWorks 的 Python 镜像在内网环境中通过可选的 pip 镜像源完成构建，同时避免把构建配置或凭证带入运行时镜像和任务沙箱。

## Scope

本设计覆盖 DataAgent backend、DataAgent runner、Portal MCP、DeepEval 和 Opik 五个会执行 `pip install` 的 Python Dockerfile，以及单架构、多架构和 quick-build 构建入口。

本设计不覆盖运行中动态安装 Python 包、带账号密码的私有 PyPI、APT/NPM 镜像、GitHub Actions 访问内网镜像源，也不改变 Python 依赖集合。

## Current State

- Python Dockerfile 默认从 pip 的默认 index 安装依赖，内网构建不方便。
- `scripts/build/docker-build.env.example` 是 quick-build 配置入口，但原始镜像源改动没有通过 `build-quick.sh` export 到子构建脚本。
- 工作区草稿把 `PIP_INDEX_URL` 写入 Dockerfile `ENV`，并通过 Compose 和 `PIP_` 前缀转发到任务沙箱。
- 草稿同时给 DataAgent backend 增加 `mcp[cli]`，但 backend 没有直接使用 MCP CLI；当前 `claude-agent-sdk` 已提供所需的 `mcp` 传递依赖。

## Problem

内网用户需要一个简单、统一的 pip 镜像配置入口，但构建期配置不能扩散成运行时安全契约。若把 index URL 固化为镜像 `ENV`，它会残留在镜像配置中；若允许 URL 携带凭证或把整个 `PIP_*` 前缀传给任务容器，还会扩大敏感信息暴露面。

## Design

### 构建契约

仅支持两个可选构建变量：

- `PIP_INDEX_URL`：pip simple index URL；
- `PIP_TRUSTED_HOST`：确需 HTTP 或未受信证书时使用的 host，不设置则保持 pip 默认 TLS 校验。

变量为空时，构建行为与当前 `main` 完全一致。变量非空时，构建脚本只把它们作为 Docker build args 传给会执行 `pip install` 的 Python 镜像。

Dockerfile 使用 `ARG`，不转换成持久化 `ENV`。这些参数仅在依赖安装层有效，最终镜像 `Config.Env` 不得包含 `PIP_INDEX_URL` 或 `PIP_TRUSTED_HOST`。

### 构建入口

- `build-images.sh` 从调用环境读取变量，用 Bash 数组安全生成可选 build args；
- `build-multiarch.sh` 使用同样的数组形式，禁止拼接未引用的参数字符串；
- `build-quick.sh` 从 `docker-build.env` 读取后只 export 这两个变量，再调用 multiarch 脚本；
- 直接执行脚本时使用 `PIP_INDEX_URL=... PIP_TRUSTED_HOST=... <script>`。

### 安全边界

- URL 不得包含用户名、密码、token 或其它凭证；第一版只支持无需认证的内网镜像或公共镜像。
- `PIP_TRUSTED_HOST` 会降低对应 host 的 TLS 校验要求，只应显式配置内网 host；优先使用受信 HTTPS 镜像。
- Compose 运行服务不接收这些变量；`sandbox_runner_main.py` 不转发 `PIP_` 前缀。
- 若未来需要认证私有源，应单独设计 BuildKit secret / pip config secret，不复用 build args。

### 依赖边界

本变更不新增、删除或固定业务 Python 依赖。`mcp[cli]` 不属于镜像源能力，必须从本次改动排除。

## Interfaces / Data Model

直接构建：

```bash
PIP_INDEX_URL=https://mirror.example.internal/simple \
PIP_TRUSTED_HOST=mirror.example.internal \
bash scripts/build/build-images.sh
```

quick-build 在 `scripts/build/docker-build.env` 中配置相同变量。没有数据库、HTTP API 或运行时环境变量变更。

## Risks / Alternatives

- 内网 pip 可达但 Debian APT 或 npm 不可达时，镜像仍可能构建失败；本设计只解决 Python 包源。
- Docker build args 不是秘密载体，因此明确不支持带凭证 URL。
- 未采用 Compose 运行时注入，因为当前服务和 Skill 没有经过验证的动态 pip 安装契约。
- 未采用全局 pip.conf，因为它会持久化到镜像并影响所有后续 Python 操作。

## Verification

- 聚焦测试检查五个 Python Dockerfile 声明 build args 且没有持久化 PIP `ENV`；
- 聚焦测试检查两个构建脚本使用可选参数数组，并检查 quick-build export 链；
- `bash -n` 验证三个 Shell 脚本；
- Compose 解析验证确认删除运行时 PIP 变量后配置仍有效；
- 至少构建一个真实 Python 镜像并检查最终 `Config.Env` 不包含 PIP 镜像变量；
- 静态检查确认 DataAgent requirements 未因本功能新增 MCP 依赖，沙箱未转发 `PIP_`。
