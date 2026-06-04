FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV HOME=/workspaces
ENV DATAAGENT_RUNTIME_PROJECT_CWD=/workspaces/enabled-skills
ENV DATAAGENT_SANDBOX_ROOT=/workspaces

WORKDIR /app/dataagent-backend

COPY dataagent/dataagent-backend/requirements.txt /tmp/requirements.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir -r /tmp/requirements.txt
RUN mkdir -p /workspaces && chmod 1777 /workspaces

COPY dataagent/dataagent-backend /app/dataagent-backend
COPY dataagent/.claude /app/.claude
RUN chmod +x /app/.claude/skills/opendataworks-platform-tools/bin/odw-cli

EXPOSE 8900

CMD ["sh", "-c", "alembic upgrade head && uvicorn main:app --host 0.0.0.0 --port 8900"]
