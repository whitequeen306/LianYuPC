# backend-agent 生产镜像（阶段 1：仅内网 8001，经 api-gateway 分流）
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim AS builder
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy
WORKDIR /app
COPY pyproject.toml ./
COPY app ./app
RUN uv sync --frozen --no-install-project --no-dev || uv sync --no-dev

FROM python:3.12-slim-bookworm
WORKDIR /app
COPY --from=builder /app /app
ENV PATH="/app/.venv/bin:$PATH" \
    LIANYU_AGENT_HOST=0.0.0.0 \
    LIANYU_AGENT_PORT=8001 \
    TZ=Asia/Shanghai
EXPOSE 8001
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8001/agent/health',timeout=3)" || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
