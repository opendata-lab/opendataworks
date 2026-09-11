"""
Pydantic 数据模型
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class FieldMeta(BaseModel):
    field_name: str
    field_type: str
    field_comment: Optional[str] = None
    is_primary: bool = False
    is_partition: bool = False


class TableMeta(BaseModel):
    table_id: int
    table_name: str
    table_comment: Optional[str] = None
    db_name: Optional[str] = None
    layer: Optional[str] = None
    business_domain: Optional[str] = None
    data_domain: Optional[str] = None
    fields: List[FieldMeta] = Field(default_factory=list)


class SemanticEntry(BaseModel):
    business_name: str
    table_name: Optional[str] = None
    field_name: Optional[str] = None
    synonyms: List[str] = Field(default_factory=list)
    description: Optional[str] = None


class BusinessRule(BaseModel):
    term: str
    synonyms: List[str] = Field(default_factory=list)
    definition: Optional[str] = None


class QAExample(BaseModel):
    question: str
    answer: str
    tags: List[str] = Field(default_factory=list)


class CreateTopicRequest(BaseModel):
    title: Optional[str] = None
    agent_id: Optional[str] = None
    permission_mode: Optional[str] = None


class UpdateTopicRequest(BaseModel):
    title: Optional[str] = None
    permission_mode: Optional[str] = None


class DeliverMessageRequest(BaseModel):
    topic_id: str
    content: str
    agent_id: Optional[str] = None
    provider_id: Optional[str] = None
    model: Optional[str] = None
    debug: bool = False
    database: Optional[str] = None
    execution_mode: Optional[str] = None
    permission_mode: Optional[str] = None


class PermissionDecisionRequest(BaseModel):
    request_id: str
    decision: str  # "allow" | "deny"
    note: Optional[str] = None


class PermissionDecisionResponse(BaseModel):
    task_id: str
    request_id: str
    decision: str


class QuestionAnswerRequest(BaseModel):
    request_id: str
    answers: List[Dict[str, Any]] = Field(default_factory=list)


class QuestionAnswerResponse(BaseModel):
    task_id: str
    request_id: str
    accepted: bool


class CreateTaskRequest(BaseModel):
    topic_id: str
    message_type: str
    message_content: Any
    agent_id: Optional[str] = None
    provider_id: Optional[str] = None
    model: Optional[str] = None
    debug: bool = False
    database: Optional[str] = None
    execution_mode: Optional[str] = None
    source_queue_id: Optional[str] = None
    source_schedule_id: Optional[str] = None
    source_schedule_log_id: Optional[str] = None


class ExecuteQueryRequest(BaseModel):
    sql: str
    database: str
    engine: Optional[str] = None
    limit: Optional[int] = None
    timeout_seconds: Optional[int] = None
    topic_id: Optional[str] = None


class MessageQueueQueryRequest(BaseModel):
    topic_id: Optional[str] = None
    page: int = 1
    page_size: int = 50


class MessageQueueUpsertRequest(BaseModel):
    topic_id: str
    message_type: str
    message_content: Any


class MessageScheduleQueryRequest(BaseModel):
    topic_id: Optional[str] = None
    page: int = 1
    page_size: int = 50


class MessageScheduleUpsertRequest(BaseModel):
    topic_id: str
    name: str
    message_type: str
    message_content: Any
    cron_expr: str
    enabled: bool = True
    timezone: str = "Asia/Shanghai"


class MessageScheduleLogsQueryRequest(BaseModel):
    page: int = 1
    page_size: int = 50


class ProviderSettingsUpdate(BaseModel):
    provider_id: str
    provider_type: Optional[str] = None
    name: Optional[str] = None
    display_name: Optional[str] = None
    provider_group: Optional[str] = None
    enabled: Optional[bool] = None
    provider_enabled: Optional[bool] = None
    enabled_models: List[str] = Field(default_factory=list)
    custom_models: List[str] = Field(default_factory=list)
    api_key: Optional[str] = None
    auth_token: Optional[str] = None
    base_url: Optional[str] = None
    supports_partial_messages: Optional[bool] = None
    model_detections: Optional[Dict[str, "ModelDetectionState"]] = None
    models: Optional[List[Any]] = None


class SettingsUpdateRequest(BaseModel):
    provider_id: Optional[str] = None
    model: Optional[str] = None
    anthropic_api_key: Optional[str] = None
    anthropic_auth_token: Optional[str] = None
    anthropic_base_url: Optional[str] = None
    mysql_host: Optional[str] = None
    mysql_port: Optional[int] = None
    mysql_user: Optional[str] = None
    mysql_password: Optional[str] = None
    mysql_database: Optional[str] = None
    doris_host: Optional[str] = None
    doris_port: Optional[int] = None
    doris_user: Optional[str] = None
    doris_password: Optional[str] = None
    doris_database: Optional[str] = None
    skills_output_dir: Optional[str] = None
    providers: Optional[List[ProviderSettingsUpdate]] = None


class ModelDetectionState(BaseModel):
    status: str = "unverified"
    message: str = ""
    checked_at: str = ""


class ProviderConfig(BaseModel):
    provider_id: str
    provider_type: str = "anthropic_compatible"
    name: str = ""
    display_name: str
    provider_group: str = ""
    base_url: str = ""
    api_key_set: bool = False
    auth_token_set: bool = False
    models: List[Any] = Field(default_factory=list)
    enabled_models: List[str] = Field(default_factory=list)
    supported_models: List[str] = Field(default_factory=list)
    custom_models: List[str] = Field(default_factory=list)
    default_model: str = ""
    enabled: bool = False
    provider_enabled: bool = False
    supports_partial_messages: bool = True
    validation_status: str = "unverified"
    validation_message: str = ""
    model_detections: Dict[str, ModelDetectionState] = Field(default_factory=dict)


class ProviderCreateRequest(ProviderSettingsUpdate):
    pass


class ProviderUpdateRequest(BaseModel):
    provider_type: Optional[str] = None
    name: Optional[str] = None
    display_name: Optional[str] = None
    provider_group: Optional[str] = None
    enabled: Optional[bool] = None
    provider_enabled: Optional[bool] = None
    enabled_models: Optional[List[str]] = None
    custom_models: Optional[List[str]] = None
    api_key: Optional[str] = None
    auth_token: Optional[str] = None
    base_url: Optional[str] = None
    supports_partial_messages: Optional[bool] = None
    model_detections: Optional[Dict[str, "ModelDetectionState"]] = None
    models: Optional[List[Any]] = None


class ProviderListResponse(BaseModel):
    providers: List[ProviderConfig] = Field(default_factory=list)


class McpServerConfig(BaseModel):
    server_id: str
    name: str
    source: str
    transport: str
    url: str = ""
    headers: Dict[str, str] = Field(default_factory=dict)
    command: str = ""
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    enabled: bool = True
    oauth_required: bool = False
    tool_count: int = 0
    description: str = ""


class McpServerCreateRequest(BaseModel):
    server_id: Optional[str] = None
    name: str
    transport: str
    url: str = ""
    headers: Dict[str, str] = Field(default_factory=dict)
    command: str = ""
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    enabled: bool = True
    oauth_required: bool = False
    tool_count: int = 0
    description: str = ""


class McpServerUpdateRequest(BaseModel):
    name: Optional[str] = None
    transport: Optional[str] = None
    url: Optional[str] = None
    headers: Optional[Dict[str, str]] = None
    command: Optional[str] = None
    args: Optional[List[str]] = None
    env: Optional[Dict[str, str]] = None
    enabled: Optional[bool] = None
    oauth_required: Optional[bool] = None
    tool_count: Optional[int] = None
    description: Optional[str] = None


class McpServerListResponse(BaseModel):
    configured: List[McpServerConfig] = Field(default_factory=list)
    plugin: List[McpServerConfig] = Field(default_factory=list)


class ModelDetectionRequest(BaseModel):
    provider_id: str
    model: str
    api_key: Optional[str] = None
    auth_token: Optional[str] = None
    base_url: Optional[str] = None
    supports_partial_messages: Optional[bool] = None


class ModelDetectionResponse(BaseModel):
    provider_id: str
    model: str
    status: str
    message: str
    checked_at: str


class SettingsResponse(BaseModel):
    default_provider_id: str
    default_model: str
    providers: List[ProviderConfig] = Field(default_factory=list)
    skills_output_dir: str = ""
    mysql_host: str = ""
    mysql_port: int = 3306
    mysql_database: str = ""
    doris_host: str = ""
    doris_port: int = 9030
    doris_database: str = ""


class RuntimeProviderConfig(BaseModel):
    provider_id: str
    display_name: str
    provider_group: str = ""
    models: List[str] = Field(default_factory=list)
    default_model: str = ""
    enabled: bool = False
    provider_enabled: bool = False
    supports_partial_messages: bool = True
    validation_status: str = "unverified"
    validation_message: str = ""


class RuntimeConfigResponse(BaseModel):
    default_provider_id: str = ""
    default_model: str = ""
    providers: List[RuntimeProviderConfig] = Field(default_factory=list)


class WidgetAllowedSite(BaseModel):
    website_id: str = ""
    allowed_origins: List[str] = Field(default_factory=list)
    project_name: str = ""
    project_color: str = ""
    allow_anonymous: bool = False


class AdminSettingsResponse(BaseModel):
    provider_id: str
    model: str
    providers: List[ProviderConfig] = Field(default_factory=list)
    widget_allowed_sites: List[WidgetAllowedSite] = Field(default_factory=list)
    anthropic_api_key: str = ""
    anthropic_auth_token: str = ""
    anthropic_base_url: str = ""
    mysql_host: str = ""
    mysql_port: int = 3306
    mysql_user: str = ""
    mysql_password: str = ""
    mysql_database: str = ""
    doris_host: str = ""
    doris_port: int = 9030
    doris_user: str = ""
    doris_password: str = ""
    doris_database: str = ""
    skills_output_dir: str = ""
    session_mysql_database: str = ""
    settings_file_path: str = ""
    settings_local_file_path: str = ""
    skills_root_dir: str = ""
    updated_at: str = ""


class AdminSettingsUpdateRequest(BaseModel):
    provider_id: Optional[str] = None
    model: Optional[str] = None
    anthropic_api_key: Optional[str] = None
    anthropic_auth_token: Optional[str] = None
    anthropic_base_url: Optional[str] = None
    mysql_host: Optional[str] = None
    mysql_port: Optional[int] = None
    mysql_user: Optional[str] = None
    mysql_password: Optional[str] = None
    mysql_database: Optional[str] = None
    doris_host: Optional[str] = None
    doris_port: Optional[int] = None
    doris_user: Optional[str] = None
    doris_password: Optional[str] = None
    doris_database: Optional[str] = None
    skills_output_dir: Optional[str] = None
    providers: Optional[List[ProviderSettingsUpdate]] = None
    widget_allowed_sites: Optional[List[WidgetAllowedSite]] = None


class SkillDocumentVersionSummary(BaseModel):
    id: int
    document_id: int
    version_no: int
    change_source: str
    change_summary: str = ""
    actor: str = ""
    content_hash: str = ""
    file_size: int = 0
    metadata: Optional[Dict[str, Any]] = None
    parent_version_id: Optional[int] = None
    created_at: str = ""
    is_current: bool = False


class SkillDocumentSummary(BaseModel):
    id: int
    folder: str = ""
    # What the skill is for, from its SKILL.md front matter. Its absence here is
    # why the list showed last_change_summary instead — a reindex note reading
    # "发现磁盘文件" on every row, which looked like a description and was not.
    description: str = ""
    relative_path: str
    file_name: str
    category: str
    content_type: str
    source: str = "bundled"
    current_hash: str = ""
    current_version_id: Optional[int] = None
    version_count: int = 0
    last_change_source: str = ""
    last_change_summary: str = ""
    created_at: str = ""
    updated_at: str = ""
    editable: bool = True
    enabled: bool = False


class SkillDocumentDetail(SkillDocumentSummary):
    current_content: str = ""
    versions: List[SkillDocumentVersionSummary] = Field(default_factory=list)


class SkillDocumentUpdateRequest(BaseModel):
    content: str
    change_summary: Optional[str] = None


class SkillRuntimeUpdateRequest(BaseModel):
    enabled: bool = True


class SkillRuntimeConfig(BaseModel):
    skill_id: str
    enabled: bool = False


class SkillImportResponse(BaseModel):
    skill_id: str
    source: str = "managed"
    enabled: bool = False
    replaced: bool = False
    version: str = ""
    previous_version: str = ""
    imported_documents: List[SkillDocumentSummary] = Field(default_factory=list)
    document_count: int = 0


class SkillUninstallResponse(BaseModel):
    skill_id: str
    removed_documents: List[SkillDocumentSummary] = Field(default_factory=list)
    was_enabled: bool = False
    document_count: int = 0


class SkillDocumentCompareRequest(BaseModel):
    left_version_id: Optional[int] = None
    right_version_id: Optional[int] = None


class SkillDocumentCompareResponse(BaseModel):
    document_id: int
    left_label: str
    right_label: str
    left_content: str = ""
    right_content: str = ""
    diff_text: str = ""
    added_lines: int = 0
    removed_lines: int = 0
    changed_lines: int = 0


class AgentSummary(BaseModel):
    agent_id: str
    name: str
    description: str = ""
    is_default: bool = False
    is_builtin: bool = False


class AgentCatalogProfile(AgentSummary):
    """Anonymous-safe profile used by Chat and Widget agent selectors."""

    preset_questions: List[str] = Field(default_factory=list)


class AgentProfileBase(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    system_prompt: Optional[str] = None
    allowed_tools: Optional[List[str]] = None
    mcp_server_ids: Optional[List[str]] = None
    skill_folders: Optional[List[str]] = None
    max_turns: Optional[int] = None
    env_vars: Optional[Dict[str, str]] = None
    data_scope: Optional[Dict[str, Any]] = None
    visibility: Optional[Dict[str, Any]] = None
    preset_questions: Optional[List[str]] = None


class AgentProfileCreateRequest(AgentProfileBase):
    name: str


class AgentProfileUpdateRequest(AgentProfileBase):
    pass


class AgentSkillCapability(BaseModel):
    folder: str
    source: str = "bundled"
    enabled: bool = False


class AgentMcpServerCapability(BaseModel):
    id: str
    name: str
    enabled: bool = False
    tool_names: List[str] = Field(default_factory=list)


class AgentCapabilitiesResponse(BaseModel):
    tools: List[str] = Field(default_factory=list)
    mcp_servers: List[AgentMcpServerCapability] = Field(default_factory=list)
    skills: List[AgentSkillCapability] = Field(default_factory=list)


class AgentDataScopeOption(BaseModel):
    cluster_id: Optional[int] = None
    cluster_name: str = ""
    source_type: str = ""
    database: str = ""


class AgentProfile(BaseModel):
    agent_id: str
    name: str
    description: str = ""
    system_prompt: str = ""
    allowed_tools: List[str] = Field(default_factory=list)
    mcp_server_ids: List[str] = Field(default_factory=list)
    skill_folders: List[str] = Field(default_factory=list)
    max_turns: int = 0
    env_vars: Dict[str, str] = Field(default_factory=dict)
    data_scope: Dict[str, Any] = Field(default_factory=lambda: {"allowed_scopes": []})
    visibility: Dict[str, Any] = Field(
        default_factory=lambda: {"mode": "all", "allowed_users": [], "allowed_groups": []}
    )
    preset_questions: List[str] = Field(default_factory=list)
    is_default: bool = False
    is_builtin: bool = False


class AgentReadableProfile(BaseModel):
    """Authenticated read-only profile. Environment variables stay admin-only;
    the visibility allow-list is admin-only too — non-admin readers only get
    the ``visibility_mode`` summary."""

    agent_id: str
    name: str
    description: str = ""
    system_prompt: str = ""
    allowed_tools: List[str] = Field(default_factory=list)
    mcp_server_ids: List[str] = Field(default_factory=list)
    skill_folders: List[str] = Field(default_factory=list)
    max_turns: int = 0
    data_scope: Dict[str, Any] = Field(default_factory=lambda: {"allowed_scopes": []})
    visibility_mode: str = "all"
    preset_questions: List[str] = Field(default_factory=list)
    is_default: bool = False
    is_builtin: bool = False
    created_at: str = ""
    updated_at: str = ""


class AgentSlashCommandsResponse(BaseModel):
    # Authoritative slash-command names for an agent. ``source`` is "sdk" when the
    # list came from a real run's system/init message, or "fallback" when derived
    # from the agent's enabled skill folders before any run reported it.
    slash_commands: List[str] = Field(default_factory=list)
    source: str = ""


class WorkspaceFile(BaseModel):
    name: str
    rel_path: str
    size: int = 0
    modified_at: str = ""
    content_type: str = "application/octet-stream"
    kind: str = "output"


class WorkspaceFileListResponse(BaseModel):
    files: List[WorkspaceFile] = Field(default_factory=list)


class TopicMessage(BaseModel):
    message_id: str
    topic_id: str
    task_id: Optional[str] = None
    sender_type: str
    type: str
    status: str = "success"
    content: str = ""
    event: str = ""
    steps: Optional[List[Dict[str, Any]]] = None
    tool: Optional[Dict[str, Any]] = None
    seq_id: int = 0
    correlation_id: Optional[str] = None
    parent_correlation_id: Optional[str] = None
    content_type: Optional[str] = None
    usage: Optional[Dict[str, Any]] = None
    blocks: Optional[List[Dict[str, Any]]] = None
    # The records `blocks` were projected from. Clients replay these through the
    # same reducer the live stream uses; `blocks` stays until every client has
    # switched, so the change can be reverted without touching stored data.
    records: Optional[List[Dict[str, Any]]] = None
    resume_after_seq: int = 0
    show_in_ui: bool = True
    feedback: str = ""
    # Files this message's run generated in the topic workspace (per-run diff),
    # so the chat surface can offer direct downloads on the message itself.
    attachments: List[WorkspaceFile] = Field(default_factory=list)
    error: Optional[Dict[str, Any]] = None
    created_at: str = ""
    updated_at: str = ""


class TopicSummary(BaseModel):
    topic_id: str
    title: str
    chat_topic_id: str
    chat_conversation_id: str
    agent_id: str = ""
    agent: Optional[AgentSummary] = None
    permission_mode: str = "default"
    current_task_id: Optional[str] = None
    current_task_status: Optional[str] = None
    message_count: int = 0
    last_message_preview: str = ""
    created_at: str = ""
    updated_at: str = ""


class TopicDetail(BaseModel):
    topic_id: str
    title: str
    chat_topic_id: str
    chat_conversation_id: str
    agent_id: str = ""
    agent: Optional[AgentSummary] = None
    permission_mode: str = "default"
    current_task_id: Optional[str] = None
    current_task_status: Optional[str] = None
    created_at: str = ""
    updated_at: str = ""


class TopicMessagePageResponse(BaseModel):
    topic_id: str
    page: int = 1
    page_size: int = 200
    order: str = "asc"
    total: int = 0
    items: List[TopicMessage] = Field(default_factory=list)


class AdminWidgetTopicSummary(TopicSummary):
    """Topic summary for the admin (read-only) widget-session view. Extends
    TopicSummary with the isolation dimensions so operators can tell which
    site / external user / visitor a conversation belongs to."""

    source: str = "portal"
    website_id: str = ""
    external_user_id: str = ""
    visitor_id: str = ""
    auth_user_id: str = ""
    auth_display_name: str = ""


class AdminWidgetTopicPage(BaseModel):
    items: List[AdminWidgetTopicSummary] = Field(default_factory=list)
    total: int = 0
    page: int = 1
    page_size: int = 20


class AdminWidgetUser(BaseModel):
    """A distinct widget user (logged-in external user or anonymous visitor)
    for the admin user filter, with how many conversations they own."""

    kind: str = "vis"          # 'ext' (external_user_id) | 'vis' (visitor_id)
    user_id: str = ""
    topic_count: int = 0


class AdminWidgetUserList(BaseModel):
    items: List[AdminWidgetUser] = Field(default_factory=list)


class AdminAuthUser(BaseModel):
    """A distinct authenticated user (namespaced stable id from topic
    ownership) for the agent visibility allow-list picker."""

    user_id: str = ""
    display_name: str = ""
    topic_count: int = 0
    last_active_at: str = ""


class AdminAuthUserList(BaseModel):
    items: List[AdminAuthUser] = Field(default_factory=list)


class UpdateMessageFeedbackRequest(BaseModel):
    feedback: str = ""


class FollowupSuggestionsResponse(BaseModel):
    topic_id: str
    message_id: str
    suggestions: List[str] = Field(default_factory=list)
    source: str = "empty"


class TaskSubmissionResponse(BaseModel):
    accepted: bool = True
    topic_id: str
    task_id: str
    task_status: str
    user_message_id: str
    assistant_message_id: str


class TaskStatusResponse(BaseModel):
    task_id: str
    topic_id: str
    agent_id: str = ""
    agent: Optional[AgentSummary] = None
    from_task_id: Optional[str] = None
    task_status: str
    prompt: str = ""
    provider_id: str = ""
    model: str = ""
    database_hint: Optional[str] = None
    cancel_requested_at: Optional[str] = None
    started_at: Optional[str] = None
    heartbeat_at: Optional[str] = None
    finished_at: Optional[str] = None
    error: Optional[Dict[str, Any]] = None
    created_at: str = ""
    updated_at: str = ""


class CancelTaskResponse(BaseModel):
    task_id: str
    task_status: str
    cancel_requested_at: Optional[str] = None


class MessageQueueRecord(BaseModel):
    queue_id: str
    topic_id: str
    agent_id: str = ""
    agent: Optional[AgentSummary] = None
    message_type: str
    message_content: Any = None
    status: str
    last_task_id: Optional[str] = None
    error_message: Optional[str] = None
    source_schedule_id: Optional[str] = None
    source_schedule_log_id: Optional[str] = None
    created_at: str = ""
    updated_at: str = ""


class MessageQueuePageResponse(BaseModel):
    page: int = 1
    page_size: int = 50
    total: int = 0
    list: List[MessageQueueRecord] = Field(default_factory=list)


class MessageScheduleRecord(BaseModel):
    schedule_id: str
    topic_id: str
    agent_id: str = ""
    agent: Optional[AgentSummary] = None
    name: str
    message_type: str
    message_content: Any = None
    cron_expr: str
    enabled: bool = True
    timezone: str = "Asia/Shanghai"
    last_task_id: Optional[str] = None
    last_queue_id: Optional[str] = None
    last_run_at: Optional[str] = None
    next_run_at: Optional[str] = None
    last_error_message: Optional[str] = None
    created_at: str = ""
    updated_at: str = ""


class MessageSchedulePageResponse(BaseModel):
    page: int = 1
    page_size: int = 50
    total: int = 0
    list: List[MessageScheduleRecord] = Field(default_factory=list)


class MessageScheduleLogRecord(BaseModel):
    schedule_log_id: str
    schedule_id: str
    queue_id: Optional[str] = None
    task_id: Optional[str] = None
    status: str
    error_message: Optional[str] = None
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    created_at: str = ""


class MessageScheduleLogPageResponse(BaseModel):
    schedule_id: str
    page: int = 1
    page_size: int = 50
    total: int = 0
    list: List[MessageScheduleLogRecord] = Field(default_factory=list)


class SdkEventRecord(BaseModel):
    seq_id: int
    turn_index: int = 0
    record_type: str
    event_type: Optional[str] = None
    data: Dict[str, Any] = Field(default_factory=dict)
    created_at: Optional[str] = None


class SdkEventPageResponse(BaseModel):
    task_id: str
    task_status: str
    after_id: int
    next_after_id: int
    has_more: bool
    records: List[SdkEventRecord] = Field(default_factory=list)


class WidgetEventItem(BaseModel):
    event_type: str
    agent_id: Optional[str] = None
    topic_id: Optional[str] = None
    task_id: Optional[str] = None
    message_id: Optional[str] = None
    payload: Optional[Dict[str, Any]] = None
    client_ts: Optional[str] = None


class WidgetEventBatchRequest(BaseModel):
    events: List[WidgetEventItem] = Field(default_factory=list)


class WidgetEventIngestResponse(BaseModel):
    accepted: int = 0


TableMeta.model_rebuild()


# ---- Evaluation ----

class EvalDatasetSummary(BaseModel):
    dataset_id: str
    name: str
    description: str = ""
    category: str = ""
    suite_tags: Any = Field(default_factory=list)
    case_count: int = 0
    dataset_hash: str = ""
    status: str = "active"
    created_by: str = ""
    created_at: Any = None
    updated_at: Any = None


class EvalDatasetCreateRequest(BaseModel):
    name: str
    description: str = ""
    category: str = ""
    suite_tags: List[str] = Field(default_factory=list)


class EvalDatasetUpdateRequest(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    category: Optional[str] = None
    suite_tags: Optional[List[str]] = None
    status: Optional[str] = None


class EvalCaseSummary(BaseModel):
    id: int = 0
    dataset_id: str = ""
    case_id: str = ""
    case_type: str = ""
    category: str = ""
    suite_tags: Any = Field(default_factory=list)
    question: str = ""
    updated_at: Any = None


class EvalCaseDetail(BaseModel):
    id: int = 0
    dataset_id: str = ""
    case_id: str = ""
    case_type: str = ""
    category: str = ""
    suite_tags: Any = Field(default_factory=list)
    question: str = ""
    turns: Any = None
    case_json: Any = None
    updated_at: Any = None


class EvalCaseUpsertRequest(BaseModel):
    case_json: Dict[str, Any]


class EvalCasesReplaceRequest(BaseModel):
    cases: List[Dict[str, Any]]


class EvalImportResponse(BaseModel):
    dataset_id: str
    name: str
    case_count: int = 0
    dataset_hash: str = ""
    errors: List[str] = Field(default_factory=list)


class EvalRunSummary(BaseModel):
    run_id: str
    dataset_id: str = ""
    dataset_hash: str = ""
    run_label: str = ""
    environment_label: str = ""
    evaluation_engine: str = ""
    engine_version: str = ""
    model: str = ""
    judge_model: str = ""
    run_status: str = ""
    passed: bool = False
    recommendation: str = ""
    total_cases: int = 0
    passed_cases: int = 0
    failed_cases: int = 0
    average_score: float = 0.0
    started_at: Any = None
    ingested_at: Any = None


class EvalRunDetail(BaseModel):
    run_id: str
    dataset_id: str = ""
    dataset_hash: str = ""
    run_label: str = ""
    environment_label: str = ""
    evaluation_engine: str = ""
    engine_version: str = ""
    model: str = ""
    judge_model: str = ""
    judge_prompt_version: str = ""
    metric_semantics_version: str = ""
    concurrency: int = 1
    run_status: str = ""
    passed: bool = False
    recommendation: str = ""
    total_cases: int = 0
    passed_cases: int = 0
    failed_cases: int = 0
    veto_count: int = 0
    judge_failed_count: int = 0
    average_score: float = 0.0
    metrics_json: Any = None
    summary_json: Any = None
    started_at: Any = None
    ingested_at: Any = None


class EvalRunIngestRequest(BaseModel):
    run_id: Optional[str] = None
    started_at: Optional[str] = None
    summary: Dict[str, Any]
    cases: List[Dict[str, Any]] = Field(default_factory=list)


class EvalRunCaseSummary(BaseModel):
    id: int = 0
    run_id: str = ""
    case_id: str = ""
    category: str = ""
    score: float = 0.0
    case_passed: bool = False
    task_status: str = ""
    hallucination: bool = False
    dimension_scores_json: Any = None
    failure_attribution: List[str] = []
    judge_comment: str = ""


class EvalRunCaseDetail(BaseModel):
    id: int = 0
    run_id: str = ""
    case_id: str = ""
    category: str = ""
    score: float = 0.0
    case_passed: bool = False
    task_status: str = ""
    hallucination: bool = False
    dimension_scores_json: Any = None
    case_json: Any = None


class EvalTrendPoint(BaseModel):
    run_id: str = ""
    run_label: str = ""
    evaluation_engine: str = ""
    model: str = ""
    average_score: float = 0.0
    passed: bool = False
    total_cases: int = 0
    passed_cases: int = 0
    failed_cases: int = 0
    intent_accuracy: Optional[float] = None
    ontology_accuracy: Optional[float] = None
    relation_accuracy: Optional[float] = None
    hallucination_rate: float = 0.0
    result_consistency_rate: Optional[float] = None
    data_accuracy: Optional[float] = None
    effective_pass_rate: Optional[float] = None
    completion_rate: Optional[float] = None
    time_accuracy: Optional[float] = None
    answer_accuracy: Optional[float] = None
    tool_sql_accuracy: Optional[float] = None
    avg_e2e_seconds: Optional[float] = None
    p90_e2e_seconds: Optional[float] = None
    avg_execution_seconds: Optional[float] = None
    avg_judge_seconds: Optional[float] = None
    avg_agent_turns: Optional[float] = None
    avg_user_turns: Optional[float] = None
    avg_tool_calls: Optional[float] = None
    avg_sql_executions: Optional[float] = None
    avg_input_tokens: Optional[float] = None
    avg_output_tokens: Optional[float] = None
    avg_cache_tokens: Optional[float] = None
    time: str = ""
    started_at: Any = None
    ingested_at: Any = None
