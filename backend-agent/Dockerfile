# backend-agent 生产镜像（阶段 1：仅内网 8001，经 api-gateway 分流）
# 用官方 python 镜像 + pip 装 uv，避免 ghcr.io 在部分网络下不可达
FROM python:3.12-slim-bookworm AS builder
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy \
    PIP_DEFAULT_TIMEOUT=120 PIP_NO_CACHE_DIR=1
WORKDIR /app
RUN pip install --no-cache-dir uv
COPY pyproject.toml ./
COPY uv.lock ./
COPY app ./app
RUN uv sync --frozen --no-install-project --no-dev

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
