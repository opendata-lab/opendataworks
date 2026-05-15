FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app/tools/dataagent-evals/deepeval

COPY tools/dataagent-evals/deepeval/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY tools/dataagent-evals /app/tools/dataagent-evals

ENTRYPOINT ["python", "/app/tools/dataagent-evals/deepeval/run.py"]
