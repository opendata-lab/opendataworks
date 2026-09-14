FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app/tools/dataagent-evals/opik

COPY tools/dataagent-evals/opik/requirements.txt /tmp/requirements.txt
ARG PIP_INDEX_URL
ARG PIP_TRUSTED_HOST
RUN PIP_INDEX_URL="${PIP_INDEX_URL}" PIP_TRUSTED_HOST="${PIP_TRUSTED_HOST}" \
    pip install --no-cache-dir -r /tmp/requirements.txt

COPY tools/dataagent-evals /app/tools/dataagent-evals

ENTRYPOINT ["python", "/app/tools/dataagent-evals/opik/run.py"]
