"""DataAgent 外置基础配置（仓库自带，参考 Superset docker/pythonpath_dev 模式）。

本文件由 compose 以目录形式挂载进 dataagent-backend 容器
（`./docker/dataagent` → `/app/docker/dataagent`），并经
`DATAAGENT_CONFIG=/app/docker/dataagent/dataagent_config.py` 指定为外置配置
入口。加载器会把本目录加入 `sys.path`，因此文件末尾可以像 Superset 的
`superset_config.py` 一样加载同目录下的用户扩展模块。

可配置内容：
- 认证：`AUTH_ENABLED` / `SECRET_KEY` / `LOCAL_ADMINS` / `OAUTH_PROVIDERS` /
  `OAUTH_USER_INFO` / `ADMIN_USERS`（UserInfo 钩子见
  custom_sso_user_info.py.example）。
- 其他运行时配置：`DATAAGENT_SETTINGS = {"<config.py Settings 字段>": 值}`，
  启动时统一应用（如超时/并发档位），字段名见
  独立 opendataagent 仓库的 `dataagent-backend/config.py`。

使用方式（不要直接改本文件，升级时会被覆盖）：
1. 拷贝 `dataagent_config_docker.py.example` 为 `dataagent_config_docker.py`
   （该文件已被 .gitignore 忽略，不会入库），按注释填写。
2. 重启 dataagent-backend。

语义（fail-closed，详见 docs/design/2026-07-01-dataagent-auth-design.md）：
- 容器内未设置 DATAAGENT_CONFIG env → 认证关闭、无任何覆盖，行为与历史版本
  完全一致（彻底回滚手段：注释掉 compose 里的该 env）。
- env 已设置但文件不可读 / 配置非法 / 用户扩展有语法错误 / 启用认证却缺合法
  SECRET_KEY / DATAAGENT_SETTINGS 含未知字段 → 服务启动失败，绝不静默降级。
- 本文件默认 AUTH_ENABLED = False：目录默认挂载也不改变现状（显式关闭态）。
"""
import logging

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 默认值（可被 dataagent_config_docker.py 覆盖）
# ---------------------------------------------------------------------------
AUTH_ENABLED = False

SECRET_KEY = ""

SESSION_TTL_SECONDS = 28800

COOKIE_NAME = "da_session"
COOKIE_SECURE = False
COOKIE_SAMESITE = "lax"

LOCAL_ADMINS = []
OAUTH_PROVIDERS = []
OAUTH_USER_INFO = None
ADMIN_USERS = []

# 非认证的运行时 Settings 覆盖（config.py 字段），默认不覆盖任何值。
DATAAGENT_SETTINGS = {}

# ---------------------------------------------------------------------------
# 加载同目录下的用户扩展（Superset superset_config_docker.py 同款机制）。
# 先用 find_spec 判断覆盖文件是否存在：不存在 → 按上面的默认值运行（认证关闭）；
# 存在 → 直接 import，任何错误（语法错误、覆盖文件内部的 import 失败，如
# `from custom_sso_user_info import ...` 但文件缺失）都原样抛出，让启动失败
# （fail-closed）。不能用 try/except ImportError 包住 import：那会把覆盖文件
# 内部的导入失败也吞掉，静默回落到认证关闭。
# ---------------------------------------------------------------------------
import importlib.util as _importlib_util

if _importlib_util.find_spec("dataagent_config_docker") is not None:
    import dataagent_config_docker
    from dataagent_config_docker import *  # noqa: F401,F403

    logger.info(
        "Loaded your DataAgent configuration override at [%s]",
        dataagent_config_docker.__file__,
    )
else:
    logger.info("Using default DataAgent config (no dataagent_config_docker.py found)")
