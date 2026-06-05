FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV HOME=/workspaces
ENV DATAAGENT_SANDBOX_ROOT=/workspaces

WORKDIR /opt/dataagent-backend

COPY dataagent/dataagent-backend/requirements.txt /tmp/requirements.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir -r /tmp/requirements.txt
RUN mkdir -p /workspaces /app/.claude/skills && chmod 1777 /workspaces

COPY dataagent/dataagent-backend /opt/dataagent-backend

EXPOSE 8900

CMD ["sh", "-c", "alembic upgrade head && uvicorn main:app --host 0.0.0.0 --port 8900"]
