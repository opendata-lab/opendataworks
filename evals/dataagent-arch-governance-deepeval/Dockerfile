FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app/evals/dataagent-arch-governance-deepeval

COPY evals/dataagent-arch-governance-deepeval/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY evals /app/evals

ENTRYPOINT ["python", "/app/evals/dataagent-arch-governance-deepeval/run.py"]
