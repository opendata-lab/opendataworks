-- MCP authorizes by service-account token only; the agent and user a caller reports are kept for audit.
ALTER TABLE data_query_history
    ADD COLUMN reported_agent_id VARCHAR(191) NULL COMMENT '调用方上报的 X-Agent-Id，未认证，仅审计',
    ADD COLUMN reported_user_id VARCHAR(255) NULL COMMENT '调用方上报的 X-Agent-User-Id，未认证，仅审计';
