FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV HOME=/tmp/dataagent-home
ENV DATAAGENT_RUNTIME_PROJECT_CWD=/tmp/dataagent-home/.dataagent/runtime/enabled-skills

WORKDIR /app/dataagent-backend

COPY dataagent/dataagent-backend/requirements.txt /tmp/requirements.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir -r /tmp/requirements.txt
RUN mkdir -p /tmp/dataagent-home && chmod 1777 /tmp/dataagent-home

COPY dataagent/dataagent-backend /app/dataagent-backend
COPY dataagent/.claude /app/.claude
RUN chmod +x /app/.claude/skills/opendataworks-platform-tools/bin/odw-cli

EXPOSE 8900

CMD ["sh", "-c", "alembic upgrade head && uvicorn main:app --host 0.0.0.0 --port 8900"]
