FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app/tools/dataagent-evals/builtin

COPY tools/dataagent-evals /app/tools/dataagent-evals

ENTRYPOINT ["python", "/app/tools/dataagent-evals/builtin/run.py"]
