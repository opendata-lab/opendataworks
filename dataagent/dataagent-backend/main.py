"""
DataAgent Backend 入口 — FastAPI 应用
"""
import logging
import sys

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.admin_routes import router as admin_router
from api.auth_routes import oauth_callback_router, router as auth_router
from api.eval_routes import router as eval_router
from api.routes import router
from config import get_settings, update_settings
from core.auth import init_auth
from core.agent_profile_service import bootstrap_default_agent_profile
from core.skill_admin_service import bootstrap_admin_settings, reindex_documents_from_disk
from core.skill_admin_store import get_skill_admin_store
from core.mcp_admin_service import bootstrap_portal_mcp_server
from core.runtime_registry_store import get_runtime_registry_store
from core.skill_discovery import resolve_agent_project_cwd, resolve_skills_root_dir
from core.task_coordinator import get_task_coordinator
from core.topic_task_store import get_topic_task_store

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="DataAgent Backend",
    description="智能问数服务后端 — 基于 Claude AI 的自然语言转 SQL",
    version="1.5.0",
)

# CORS — 允许前端直接对接
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(auth_router)
app.include_router(oauth_callback_router)
app.include_router(router)
app.include_router(admin_router)
app.include_router(eval_router)


@app.get("/")
async def root():
    return {
        "service": "dataagent-backend",
        "version": "1.5.0",
        "docs": "/docs",
    }


@app.on_event("startup")
async def startup():
    """启动检查：认证配置、skills 路径、topic/task schema 与 Redis coordinator"""
    # Fail-closed：env 已设置但配置非法时抛 AuthConfigError，服务启动失败，
    # 绝不静默降级为无认证（见 docs/design/2026-07-01-dataagent-auth-design.md）。
    auth_settings = init_auth()
    logger.info(
        "Auth %s config_path=%s",
        "enabled" if auth_settings.enabled else "disabled",
        auth_settings.config_path or "<unset>",
    )
    # 外置配置里的 DATAAGENT_SETTINGS 覆盖运行时 Settings（config.py 字段），
    # 在其余 bootstrap 之前应用，保证后续步骤读到最终值。
    if auth_settings.runtime_settings:
        update_settings(auth_settings.runtime_settings)
        logger.info("Applied DATAAGENT_SETTINGS overrides: %s", sorted(auth_settings.runtime_settings))

    try:
        get_skill_admin_store().init_schema()
        get_runtime_registry_store().init_schema()
        bootstrap_admin_settings()
        portal_mcp = bootstrap_portal_mcp_server()
        logger.info("Portal MCP registry bootstrap server_id=%s", (portal_mcp or {}).get("server_id") or "<unset>")
        logger.info("Admin settings initialized")
    except Exception as e:
        logger.exception("Admin settings bootstrap failed: %s", e)

    cfg = get_settings()
    logger.info(
        "Starting DataAgent Backend on %s:%d provider=%s model=%s",
        cfg.host,
        cfg.port,
        cfg.llm_provider,
        cfg.claude_model,
    )

    try:
        skills_root = resolve_skills_root_dir()
        agent_cwd = resolve_agent_project_cwd()
        logger.info("Skills discovery ready root=%s agent_cwd=%s", skills_root, agent_cwd)
        changed = reindex_documents_from_disk()
        logger.info("Skill documents indexed changed=%s", len(changed))
    except Exception as e:
        logger.exception("Skills bootstrap failed: %s", e)

    try:
        get_topic_task_store().init_schema()
        logger.info("Topic/task store ready for schema `%s`", cfg.session_mysql_database)
    except Exception as e:
        logger.exception("Topic/task store bootstrap failed: %s", e)
        raise

    try:
        profile = bootstrap_default_agent_profile()
        logger.info("Default agent profile ready agent_id=%s", profile.get("agent_id"))
    except Exception as e:
        logger.exception("Agent profile bootstrap failed: %s", e)
        raise

    try:
        await get_task_coordinator().start()
        logger.info(
            "Task coordinator ready redis=%s:%s concurrency=%s",
            cfg.redis_host,
            cfg.redis_port,
            cfg.task_max_concurrency,
        )
    except Exception as e:
        logger.exception("Task coordinator bootstrap failed: %s", e)
        raise


@app.on_event("shutdown")
async def shutdown():
    try:
        await get_task_coordinator().stop()
    except Exception as e:
        logger.exception("Task coordinator shutdown failed: %s", e)


if __name__ == "__main__":
    import uvicorn
    cfg = get_settings()
    uvicorn.run(
        "main:app",
        host=cfg.host,
        port=cfg.port,
        reload=cfg.debug,
    )
