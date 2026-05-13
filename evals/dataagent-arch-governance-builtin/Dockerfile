FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app/evals/dataagent-arch-governance-builtin

COPY evals /app/evals

ENTRYPOINT ["python", "/app/evals/dataagent-arch-governance-builtin/run.py"]
