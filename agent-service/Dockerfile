FROM ghcr.io/astral-sh/uv:0.12.19 AS uv

FROM python:3.14.6-slim-bookworm AS builder
COPY --from=uv /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
ARG PFX_WITH_LOCAL_EMBEDDING=false
RUN --mount=type=cache,target=/root/.cache/uv \
    if [ "$PFX_WITH_LOCAL_EMBEDDING" = "true" ]; then \
      uv sync --locked --no-dev --no-editable --no-install-project --extra local-embedding; \
    else \
      uv sync --locked --no-dev --no-editable --no-install-project; \
    fi
COPY src ./src
RUN --mount=type=cache,target=/root/.cache/uv \
    if [ "$PFX_WITH_LOCAL_EMBEDDING" = "true" ]; then \
      uv sync --locked --no-dev --no-editable --extra local-embedding; \
    else \
      uv sync --locked --no-dev --no-editable; \
    fi

FROM python:3.14.6-slim-bookworm AS runtime

ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PFX_AGENT_HOST=0.0.0.0 \
    PFX_AGENT_PORT=8083

WORKDIR /app
COPY --from=builder --chown=10001:10001 /app/.venv .venv
COPY --chown=10001:10001 pyproject.toml README.md alembic.ini ./
COPY --chown=10001:10001 alembic ./alembic
COPY --chown=10001:10001 src ./src
COPY --chown=10001:10001 knowledge ./knowledge
COPY --chown=10001:10001 evaluations ./evaluations
RUN mkdir -p /app/.data/knowledge && chown -R 10001:10001 /app/.data

USER 10001:10001
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8083/health/live', timeout=2)"]

CMD ["python", "-m", "prizeflux_agent"]
