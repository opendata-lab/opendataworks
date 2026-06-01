"""add dataagent schema comments

Revision ID: 20260601_000014
Revises: 20260529_000013
Create Date: 2026-06-01 10:00:00
"""
from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

import sqlalchemy as sa
from alembic import op


revision = "20260601_000014"
down_revision = "20260529_000013"
branch_labels = None
depends_on = None


TABLE_COMMENTS: dict[str, str] = {
    "da_agent_settings": "DataAgent运行配置表",
    "da_skill_document": "DataAgent Skill文档当前版本表",
    "da_skill_document_version": "DataAgent Skill文档历史版本表",
    "da_agent_topic": "DataAgent会话主题表",
    "da_agent_task": "DataAgent任务运行表",
    "da_agent_message": "DataAgent消息表",
    "da_agent_chunk": "DataAgent流式输出分片表",
    "da_agent_message_queue": "DataAgent异步消息队列表",
    "da_agent_message_schedule": "DataAgent定时消息计划表",
    "da_agent_message_schedule_log": "DataAgent定时消息执行日志表",
    "da_agent_profile": "DataAgent智能体配置表",
    "da_agent_sdk_record": "DataAgent SDK原始流记录表",
}


COLUMN_COMMENTS: dict[str, dict[str, str]] = {
    "da_agent_settings": {
        "settings_key": "配置记录键，通常为default",
        "provider_id": "默认模型服务提供方ID",
        "model_name": "默认模型名称",
        "anthropic_api_key": "Anthropic兼容API Key",
        "anthropic_auth_token": "Anthropic兼容Auth Token",
        "anthropic_base_url": "Anthropic兼容API Base URL",
        "mysql_host": "MySQL查询工具主机",
        "mysql_port": "MySQL查询工具端口",
        "mysql_user": "MySQL查询工具用户名",
        "mysql_password": "MySQL查询工具密码",
        "mysql_database": "MySQL查询工具默认数据库",
        "doris_host": "Doris查询工具FE主机",
        "doris_port": "Doris查询工具MySQL端口",
        "doris_user": "Doris查询工具用户名",
        "doris_password": "Doris查询工具密码",
        "doris_database": "Doris查询工具默认数据库",
        "skills_output_dir": "DataAgent Skill输出目录",
        "raw_json": "完整运行配置JSON",
        "created_at": "创建时间",
        "updated_at": "更新时间",
    },
    "da_skill_document": {
        "id": "自增主键",
        "relative_path": "Skill文档相对路径",
        "file_name": "文件名",
        "category": "文档分类",
        "content_type": "内容类型",
        "current_content": "当前文档内容",
        "current_hash": "当前内容SHA-256哈希",
        "current_version_id": "当前版本ID",
        "version_count": "累计版本数",
        "last_change_source": "最近变更来源",
        "last_change_summary": "最近变更摘要",
        "created_at": "创建时间",
        "updated_at": "更新时间",
    },
    "da_skill_document_version": {
        "id": "自增主键",
        "document_id": "关联Skill文档ID",
        "version_no": "文档版本号",
        "change_source": "变更来源",
        "change_summary": "变更摘要",
        "actor": "变更操作者",
        "content": "版本内容",
        "content_hash": "版本内容SHA-256哈希",
        "file_size": "文件大小，单位字节",
        "metadata_json": "版本元数据JSON",
        "parent_version_id": "父版本ID",
        "created_at": "创建时间",
    },
    "da_agent_topic": {
        "topic_id": "会话主题ID",
        "title": "会话标题",
        "chat_topic_id": "前端聊天主题ID",
        "chat_conversation_id": "外部聊天会话ID",
        "current_task_id": "当前任务ID",
        "current_task_status": "当前任务状态",
        "last_message_seq": "主题内最新消息序号",
        "created_at": "创建时间",
        "updated_at": "更新时间",
        "source": "主题来源，portal或widget",
        "website_id": "嵌入站点ID",
        "external_user_id": "外部用户ID",
        "visitor_id": "访客ID",
        "agent_id": "关联智能体ID",
        "agent_snapshot_json": "主题创建时的智能体快照JSON",
    },
    "da_agent_task": {
        "task_id": "任务ID",
        "topic_id": "关联会话主题ID",
        "from_task_id": "来源任务ID",
        "source_queue_id": "来源消息队列ID",
        "source_schedule_id": "来源定时计划ID",
        "source_schedule_log_id": "来源定时执行日志ID",
        "task_status": "任务状态",
        "prompt": "用户输入或任务提示词",
        "provider_id": "模型服务提供方ID",
        "model_name": "模型名称",
        "database_hint": "数据库选择提示",
        "debug_enabled": "是否启用调试模式",
        "timeout_seconds": "任务总超时时间，单位秒",
        "sql_read_timeout_seconds": "只读SQL超时时间，单位秒",
        "sql_write_timeout_seconds": "写入SQL超时时间，单位秒",
        "last_event_seq": "最新事件序号",
        "cancel_requested_at": "请求取消时间",
        "started_at": "任务开始时间",
        "heartbeat_at": "任务心跳时间",
        "finished_at": "任务结束时间",
        "error_json": "错误详情JSON",
        "created_at": "创建时间",
        "updated_at": "更新时间",
        "agent_id": "执行智能体ID",
        "agent_snapshot_json": "任务执行时的智能体快照JSON",
    },
    "da_agent_message": {
        "message_id": "消息ID",
        "topic_id": "关联会话主题ID",
        "task_id": "关联任务ID",
        "sender_type": "发送方类型",
        "type": "消息类型",
        "status": "消息状态",
        "content": "消息内容",
        "event": "事件名称",
        "steps_json": "步骤结构JSON",
        "tool_json": "工具调用JSON",
        "seq_id": "消息序号",
        "correlation_id": "当前关联ID",
        "parent_correlation_id": "父级关联ID",
        "content_type": "内容类型",
        "usage_json": "模型用量JSON",
        "error_json": "错误详情JSON",
        "show_in_ui": "是否在界面展示",
        "created_at": "创建时间",
        "updated_at": "更新时间",
        "feedback": "用户反馈标记",
    },
    "da_agent_chunk": {
        "id": "自增主键",
        "topic_id": "关联会话主题ID",
        "task_id": "关联任务ID",
        "seq_id": "分片序号",
        "request_id": "模型请求ID",
        "chunk_id": "请求内分片ID",
        "content": "分片内容",
        "delta_status": "增量状态",
        "finish_reason": "模型结束原因",
        "delta_extra_json": "增量扩展信息JSON",
        "correlation_id": "当前关联ID",
        "parent_correlation_id": "父级关联ID",
        "model_id": "模型ID",
        "content_type": "内容类型",
        "metadata_extra_json": "元数据扩展信息JSON",
        "created_at": "创建时间",
    },
    "da_agent_message_queue": {
        "queue_id": "队列消息ID",
        "topic_id": "关联会话主题ID",
        "source_schedule_id": "来源定时计划ID",
        "source_schedule_log_id": "来源定时执行日志ID",
        "message_type": "消息类型",
        "message_content_json": "消息内容JSON",
        "status": "队列状态",
        "last_task_id": "最近生成的任务ID",
        "error_message": "错误信息",
        "consumed_at": "消费时间",
        "created_at": "创建时间",
        "updated_at": "更新时间",
    },
    "da_agent_message_schedule": {
        "schedule_id": "定时计划ID",
        "topic_id": "关联会话主题ID",
        "name": "计划名称",
        "message_type": "消息类型",
        "message_content_json": "消息内容JSON",
        "cron_expr": "Cron表达式",
        "timezone": "计划时区",
        "enabled": "是否启用",
        "last_task_id": "最近生成的任务ID",
        "last_queue_id": "最近生成的队列消息ID",
        "last_run_at": "最近运行时间",
        "next_run_at": "下次运行时间",
        "last_error_message": "最近错误信息",
        "created_at": "创建时间",
        "updated_at": "更新时间",
    },
    "da_agent_message_schedule_log": {
        "schedule_log_id": "定时执行日志ID",
        "schedule_id": "关联定时计划ID",
        "queue_id": "关联队列消息ID",
        "task_id": "关联任务ID",
        "status": "执行状态",
        "error_message": "错误信息",
        "started_at": "开始时间",
        "finished_at": "结束时间",
        "created_at": "创建时间",
    },
    "da_agent_profile": {
        "agent_id": "智能体ID",
        "name": "智能体名称",
        "description": "智能体描述",
        "system_prompt": "系统提示词",
        "permission_mode": "权限模式",
        "allowed_tools_json": "允许使用的工具JSON",
        "mcp_server_ids_json": "启用的MCP服务ID列表JSON",
        "skill_folders_json": "启用的Skill目录列表JSON",
        "max_turns": "最大对话轮数，0表示使用默认值",
        "env_vars_json": "智能体环境变量JSON",
        "is_default": "是否默认智能体",
        "created_at": "创建时间",
        "updated_at": "更新时间",
        "is_builtin": "是否内置智能体",
        "data_scope_json": "智能体数据访问范围JSON",
        "preset_questions_json": "智能体预置问题JSON",
    },
    "da_agent_sdk_record": {
        "id": "自增主键",
        "topic_id": "关联会话主题ID",
        "task_id": "关联任务ID",
        "turn_index": "SDK对话轮次索引",
        "record_type": "记录类型",
        "event_type": "事件类型",
        "data": "SDK原始事件数据JSON",
        "created_at": "创建时间",
    },
}


NUMERIC_DATA_TYPES = {
    "bigint",
    "bit",
    "bool",
    "boolean",
    "decimal",
    "double",
    "float",
    "int",
    "integer",
    "mediumint",
    "numeric",
    "real",
    "smallint",
    "tinyint",
    "year",
}

UNQUOTED_DEFAULT_PATTERN = re.compile(
    r"^(?:CURRENT_TIMESTAMP|CURRENT_TIMESTAMP\(\d*\)|NOW\(\d*\)|LOCALTIME|LOCALTIME\(\d*\)|LOCALTIMESTAMP|LOCALTIMESTAMP\(\d*\))$",
    re.IGNORECASE,
)
ON_UPDATE_PATTERN = re.compile(
    r"\bon update\s+((?:CURRENT_TIMESTAMP|NOW|LOCALTIME|LOCALTIMESTAMP)(?:\(\d*\))?)",
    re.IGNORECASE,
)
MYSQL_NAME_PATTERN = re.compile(r"^[0-9A-Za-z_$]+$")


def _quote_identifier(value: str) -> str:
    return f"`{value.replace('`', '``')}`"


def _quote_literal(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def _render_mysql_name(value: Any) -> str:
    text = str(value or "").strip()
    if not MYSQL_NAME_PATTERN.fullmatch(text):
        raise ValueError(f"Unsafe MySQL identifier fragment: {text!r}")
    return text


def _render_default(row: Mapping[str, Any]) -> str:
    default = row.get("COLUMN_DEFAULT")
    if default is None:
        return ""

    text = str(default)
    data_type = str(row.get("DATA_TYPE") or "").lower()
    if UNQUOTED_DEFAULT_PATTERN.fullmatch(text):
        return f"DEFAULT {text.upper()}"
    if data_type in NUMERIC_DATA_TYPES:
        return f"DEFAULT {text}"
    return f"DEFAULT {_quote_literal(text)}"


def _render_on_update(extra: str) -> str:
    match = ON_UPDATE_PATTERN.search(extra or "")
    if not match:
        return ""
    return f"ON UPDATE {match.group(1).upper()}"


def _build_column_definition(row: Mapping[str, Any], comment: str) -> str:
    parts = [str(row["COLUMN_TYPE"])]

    character_set = row.get("CHARACTER_SET_NAME")
    if character_set:
        parts.extend(["CHARACTER SET", _render_mysql_name(character_set)])

    collation = row.get("COLLATION_NAME")
    if collation:
        parts.extend(["COLLATE", _render_mysql_name(collation)])

    parts.append("NOT NULL" if row.get("IS_NULLABLE") == "NO" else "NULL")

    default_clause = _render_default(row)
    if default_clause:
        parts.append(default_clause)

    extra = str(row.get("EXTRA") or "")
    on_update_clause = _render_on_update(extra)
    if on_update_clause:
        parts.append(on_update_clause)
    if "auto_increment" in extra.lower():
        parts.append("AUTO_INCREMENT")

    parts.extend(["COMMENT", _quote_literal(comment)])
    return " ".join(parts)


def _current_schema() -> str:
    bind = op.get_bind()
    return str(bind.execute(sa.text("SELECT DATABASE()")).scalar() or "").strip()


def _table_exists(table_name: str) -> bool:
    bind = op.get_bind()
    row = bind.execute(
        sa.text(
            """
            SELECT 1
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = :schema
              AND TABLE_NAME = :table_name
            LIMIT 1
            """
        ),
        {"schema": _current_schema(), "table_name": table_name},
    ).first()
    return row is not None


def _column_metadata(table_name: str, column_name: str) -> Mapping[str, Any] | None:
    bind = op.get_bind()
    row = bind.execute(
        sa.text(
            """
            SELECT COLUMN_TYPE,
                   DATA_TYPE,
                   IS_NULLABLE,
                   COLUMN_DEFAULT,
                   EXTRA,
                   CHARACTER_SET_NAME,
                   COLLATION_NAME
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = :schema
              AND TABLE_NAME = :table_name
              AND COLUMN_NAME = :column_name
            LIMIT 1
            """
        ),
        {"schema": _current_schema(), "table_name": table_name, "column_name": column_name},
    ).mappings().first()
    return row


def _apply_table_comment(table_name: str, comment: str) -> None:
    if not _table_exists(table_name):
        return
    op.execute(f"ALTER TABLE {_quote_identifier(table_name)} COMMENT = {_quote_literal(comment)}")


def _apply_column_comment(table_name: str, column_name: str, comment: str) -> None:
    row = _column_metadata(table_name, column_name)
    if row is None:
        return
    definition = _build_column_definition(row, comment)
    op.execute(
        f"ALTER TABLE {_quote_identifier(table_name)} "
        f"MODIFY COLUMN {_quote_identifier(column_name)} {definition}"
    )


def _apply_comments(*, remove: bool = False) -> None:
    bind = op.get_bind()
    if bind.dialect.name not in {"mysql", "mariadb"}:
        return

    empty_comment = ""
    op.execute("SET FOREIGN_KEY_CHECKS=0")
    try:
        for table_name, comment in TABLE_COMMENTS.items():
            _apply_table_comment(table_name, empty_comment if remove else comment)
        for table_name, columns in COLUMN_COMMENTS.items():
            for column_name, comment in columns.items():
                _apply_column_comment(table_name, column_name, empty_comment if remove else comment)
    finally:
        op.execute("SET FOREIGN_KEY_CHECKS=1")


def upgrade() -> None:
    _apply_comments(remove=False)


def downgrade() -> None:
    _apply_comments(remove=True)
