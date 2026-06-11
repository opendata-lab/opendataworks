from __future__ import annotations

from fastapi import APIRouter, File, HTTPException, Query, UploadFile
from fastapi.responses import Response

from core.agent_profile_service import (
    agent_capabilities,
    create_agent_profile,
    delete_agent_profile,
    get_agent_profile,
    list_data_scope_options,
    list_agent_profiles,
    skill_folders_from_documents,
    update_agent_profile,
)
from core.skill_admin_service import (
    compare_document_versions,
    current_settings_payload,
    detect_model_availability,
    export_skill_as_zip,
    get_document_detail,
    import_skill_from_zip,
    list_documents,
    list_provider_configs,
    persist_admin_settings,
    rollback_document,
    save_document_content,
    uninstall_skill,
    update_skill_runtime,
)
from core.skill_discovery import resolve_skills_root_dir
from core.topic_task_store import get_topic_task_store
from models.schemas import (
    AdminSettingsResponse,
    AdminSettingsUpdateRequest,
    AdminWidgetTopicPage,
    AdminWidgetTopicSummary,
    AdminWidgetUser,
    AdminWidgetUserList,
    AgentCapabilitiesResponse,
    AgentDataScopeOption,
    AgentProfile,
    AgentProfileCreateRequest,
    AgentProfileUpdateRequest,
    ModelDetectionRequest,
    ModelDetectionResponse,
    ProviderConfig,
    SkillDocumentCompareRequest,
    SkillDocumentCompareResponse,
    SkillDocumentDetail,
    SkillDocumentSummary,
    SkillDocumentUpdateRequest,
    SkillImportResponse,
    SkillRuntimeConfig,
    SkillRuntimeUpdateRequest,
    SkillUninstallResponse,
    TopicMessagePageResponse,
)

router = APIRouter()
settings_router = APIRouter(prefix="/api/v1/nl2sql-admin")
skills_router = APIRouter(prefix="/api/v1/dataagent")


def _provider_catalog() -> list[ProviderConfig]:
    return [ProviderConfig.model_validate(item) for item in list_provider_configs(enabled_only=False)]


def _build_admin_settings_response(updated_at: str = "") -> AdminSettingsResponse:
    payload = current_settings_payload()
    return AdminSettingsResponse(
        provider_id=str(payload.get("provider_id") or ""),
        model=str(payload.get("model") or ""),
        providers=_provider_catalog(),
        widget_allowed_sites=payload.get("widget_allowed_sites") or [],
        anthropic_api_key="",
        anthropic_auth_token="",
        anthropic_base_url=str(payload.get("anthropic_base_url") or ""),
        mysql_host=str(payload.get("mysql_host") or ""),
        mysql_port=int(payload.get("mysql_port") or 3306),
        mysql_user=str(payload.get("mysql_user") or ""),
        mysql_password="",
        mysql_database=str(payload.get("mysql_database") or ""),
        doris_host=str(payload.get("doris_host") or ""),
        doris_port=int(payload.get("doris_port") or 9030),
        doris_user=str(payload.get("doris_user") or ""),
        doris_password="",
        doris_database=str(payload.get("doris_database") or ""),
        skills_output_dir=str(payload.get("skills_output_dir") or ""),
        session_mysql_database=str(payload.get("session_mysql_database") or ""),
        settings_file_path="",
        settings_local_file_path="",
        skills_root_dir=str(resolve_skills_root_dir()),
        updated_at=updated_at,
    )


@settings_router.get("/settings", response_model=AdminSettingsResponse)
async def get_admin_settings():
    return _build_admin_settings_response()


@settings_router.put("/settings", response_model=AdminSettingsResponse)
async def update_admin_settings(request: AdminSettingsUpdateRequest):
    try:
        saved = persist_admin_settings(request.model_dump(exclude_none=True, exclude_unset=True))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return _build_admin_settings_response(updated_at=str(saved.get("updated_at") or ""))


@settings_router.post("/model-detections", response_model=ModelDetectionResponse)
async def create_model_detection(request: ModelDetectionRequest):
    try:
        result = await detect_model_availability(request.model_dump(exclude_none=True, exclude_unset=True))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ModelDetectionResponse.model_validate(result)


@settings_router.get("/widget-topics", response_model=AdminWidgetTopicPage)
async def admin_list_widget_topics(
    website_id: str | None = Query(default=None),
    external_user_id: str | None = Query(default=None),
    visitor_id: str | None = Query(default=None),
    agent_id: str | None = Query(default=None),
    keyword: str | None = Query(default=None),
    start: str | None = Query(default=None),
    end: str | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=200),
):
    """Read-only admin listing of widget-sourced conversations across all
    sites/users. Bypasses per-user isolation by design; portal session
    listing is unaffected."""
    payload = get_topic_task_store().admin_list_topics(
        source="widget",
        website_id=website_id,
        external_user_id=external_user_id,
        visitor_id=visitor_id,
        agent_id=agent_id,
        keyword=keyword,
        start=start,
        end=end,
        page=page,
        page_size=page_size,
    )
    return AdminWidgetTopicPage(
        items=[AdminWidgetTopicSummary.model_validate(item) for item in payload.get("items") or []],
        total=int(payload.get("total") or 0),
        page=int(payload.get("page") or page),
        page_size=int(payload.get("page_size") or page_size),
    )


@settings_router.get("/widget-users", response_model=AdminWidgetUserList)
async def admin_list_widget_users(
    website_id: str | None = Query(default=None),
    keyword: str | None = Query(default=None),
    limit: int = Query(default=100, ge=1, le=500),
):
    """Distinct widget users (external users / anonymous visitors) for the
    admin user filter. Supports server-side keyword search so the dropdown
    can resolve the full user set rather than only users on the loaded page."""
    rows = get_topic_task_store().admin_list_widget_users(
        source="widget",
        website_id=website_id,
        keyword=keyword,
        limit=limit,
    )
    return AdminWidgetUserList(items=[AdminWidgetUser.model_validate(row) for row in rows])


@settings_router.get("/widget-topics/{topic_id}/messages", response_model=TopicMessagePageResponse)
async def admin_list_widget_topic_messages(
    topic_id: str,
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=200, ge=1, le=500),
    order: str = Query(default="asc", pattern="^(asc|desc)$"),
):
    """Read-only admin view of a conversation's messages. Resolved by
    topic_id without owner check, since `context=None` reuses the existing
    `1 = 1` predicate path in the store."""
    store = get_topic_task_store()
    if not store.get_topic(topic_id, context=None):
        raise HTTPException(status_code=404, detail="Topic not found")
    payload = store.list_topic_messages_page(
        topic_id=topic_id, page=page, page_size=page_size, order=order, context=None
    )
    return TopicMessagePageResponse.model_validate(payload)


@skills_router.get("/skills/documents", response_model=list[SkillDocumentSummary])
async def get_skill_documents():
    return [SkillDocumentSummary.model_validate(item) for item in list_documents()]


@skills_router.get("/agents/capabilities", response_model=AgentCapabilitiesResponse)
async def get_agent_capabilities():
    return AgentCapabilitiesResponse.model_validate(agent_capabilities(list_documents()))


@skills_router.get("/data-scope/options", response_model=list[AgentDataScopeOption])
async def get_data_scope_options():
    return [AgentDataScopeOption.model_validate(item) for item in list_data_scope_options()]


@skills_router.get("/agents", response_model=list[AgentProfile])
async def get_agents():
    return [AgentProfile.model_validate(item) for item in list_agent_profiles()]


@skills_router.post("/agents", response_model=AgentProfile)
async def create_agent(request: AgentProfileCreateRequest):
    try:
        profile = create_agent_profile(
            request.model_dump(exclude_none=True, exclude_unset=True),
            available_skill_folders=skill_folders_from_documents(list_documents()),
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return AgentProfile.model_validate(profile)


@skills_router.get("/agents/{agent_id}", response_model=AgentProfile)
async def get_agent(agent_id: str):
    profile = get_agent_profile(agent_id)
    if not profile:
        raise HTTPException(status_code=404, detail="agent not found")
    return AgentProfile.model_validate(profile)


@skills_router.put("/agents/{agent_id}", response_model=AgentProfile)
async def update_agent(agent_id: str, request: AgentProfileUpdateRequest):
    try:
        profile = update_agent_profile(
            agent_id,
            request.model_dump(exclude_none=True, exclude_unset=True),
            available_skill_folders=skill_folders_from_documents(list_documents()),
        )
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return AgentProfile.model_validate(profile)


@skills_router.delete("/agents/{agent_id}")
async def delete_agent(agent_id: str):
    try:
        deleted = delete_agent_profile(agent_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if not deleted:
        raise HTTPException(status_code=404, detail="agent not found")
    return {"status": "ok"}


@skills_router.get("/skills/documents/{document_id}", response_model=SkillDocumentDetail)
async def get_skill_document(document_id: int):
    document = get_document_detail(document_id)
    if not document:
        raise HTTPException(status_code=404, detail="document not found")
    return SkillDocumentDetail.model_validate(document)


@skills_router.put("/skills/documents/{document_id}", response_model=SkillDocumentDetail)
async def update_skill_document(document_id: int, request: SkillDocumentUpdateRequest):
    try:
        document = save_document_content(document_id, request.content, request.change_summary)
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return SkillDocumentDetail.model_validate(document)


@skills_router.put("/skills/runtime/{folder}", response_model=SkillRuntimeConfig)
async def update_skill_runtime_config(folder: str, request: SkillRuntimeUpdateRequest):
    try:
        result = update_skill_runtime(folder, request.enabled)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return SkillRuntimeConfig.model_validate(result)


@skills_router.post("/skills/imports", response_model=SkillImportResponse)
async def import_skill(file: UploadFile = File(...)):
    try:
        content = await file.read()
        result = import_skill_from_zip(file.filename or "", content)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return SkillImportResponse.model_validate(result)


@skills_router.get("/skills/{folder}/export")
async def export_skill(folder: str):
    try:
        file_name, content = export_skill_as_zip(folder)
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return Response(
        content=content,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{file_name}"'},
    )


@skills_router.delete("/skills/{folder}", response_model=SkillUninstallResponse)
async def delete_skill(folder: str):
    try:
        result = uninstall_skill(folder)
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return SkillUninstallResponse.model_validate(result)


@skills_router.post("/skills/documents/{document_id}/compare", response_model=SkillDocumentCompareResponse)
async def compare_skill_document(document_id: int, request: SkillDocumentCompareRequest):
    try:
        result = compare_document_versions(
            document_id,
            left_version_id=request.left_version_id,
            right_version_id=request.right_version_id,
        )
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return SkillDocumentCompareResponse.model_validate(result)


@skills_router.post("/skills/documents/{document_id}/versions/{version_id}/rollback", response_model=SkillDocumentDetail)
async def rollback_skill_document(document_id: int, version_id: int):
    try:
        document = rollback_document(document_id, version_id)
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return SkillDocumentDetail.model_validate(document)


router.include_router(settings_router)
router.include_router(skills_router)
