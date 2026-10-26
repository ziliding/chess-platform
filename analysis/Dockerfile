FROM python:3.15-slim-trixie AS base
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never PYTHONUNBUFFERED=1
# Debian's package installs the binary in /usr/games
RUN apt-get update \
    && apt-get install -y --no-install-recommends stockfish \
    && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.13 /uv /bin/uv
WORKDIR /app

# CI runs lint, type checks and tests here, against the Stockfish that ships: docker build --target test analysis
FROM base AS test
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-install-project
COPY src ./src
COPY tests ./tests
ENV PATH="/app/.venv/bin:$PATH"
CMD ["sh", "-c", "ruff check . && ruff format --check . && pyrefly check && pytest --cov=analysis --cov-fail-under=80"]

FROM base AS runtime
ARG APP_USER=appuser
ARG APP_UID=996
RUN useradd --system --uid ${APP_UID} --no-create-home ${APP_USER}
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-install-project
COPY src ./src
ENV PATH="/app/.venv/bin:$PATH" PYTHONPATH=/app/src
USER ${APP_USER}
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
    CMD python -c "import sys,urllib.request as u; sys.exit(u.urlopen('http://localhost:8000/readyz', timeout=3).status != 200)"
CMD ["uvicorn", "analysis.main:app", "--host", "0.0.0.0", "--port", "8000"]
