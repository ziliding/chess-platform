import asyncio
import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Response

from analysis.worker import Settings, Worker

# Below compose's 60 s stop_grace_period, so the container is not killed while the worker finishes cleanly.
# ponytail: a job can take up to JOB_BUDGET_SECONDS (120 s), longer than this; one still running is
# cancelled and redelivered. Raise the grace and stop_grace_period together if long games are often cut off.
SHUTDOWN_GRACE_SECONDS = 50

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    worker = Worker(Settings())
    app.state.worker = worker
    task = asyncio.create_task(worker.run())
    try:
        yield
    finally:
        # On SIGTERM: stop consuming and let the job in progress finish before the connection closes
        await worker.shutdown(SHUTDOWN_GRACE_SECONDS)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


app = FastAPI(title="chess-analysis", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/readyz")
async def readyz(response: Response) -> dict[str, bool]:
    ready = await app.state.worker.is_ready()
    if not ready:
        response.status_code = 503
    return {"ready": ready}
