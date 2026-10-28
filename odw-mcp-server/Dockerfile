FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app/odw-mcp-server

COPY odw-mcp-server/requirements.txt /tmp/requirements.txt
ARG PIP_INDEX_URL
ARG PIP_TRUSTED_HOST
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && PIP_INDEX_URL="${PIP_INDEX_URL}" PIP_TRUSTED_HOST="${PIP_TRUSTED_HOST}" \
       pip install --no-cache-dir -r /tmp/requirements.txt

COPY odw-mcp-server/ /app/odw-mcp-server

EXPOSE 8801

# uvicorn's default --timeout-keep-alive is 5s, which closes the MCP client's idle
# connection during model "thinking" gaps between tool calls and surfaces as
# `MCP server "opendataworks" session expired` deeper into a conversation. Raise it well
# above the interactive agent run timeout (360s); env-configurable for tuning.
CMD ["sh", "-c", "exec uvicorn odw_mcp_server.app:app --host 0.0.0.0 --port 8801 --timeout-keep-alive ${ODW_MCP_SERVER_KEEPALIVE_TIMEOUT_SECONDS:-600}"]
