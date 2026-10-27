import asyncio
import contextlib
import dataclasses
import json
import os
import signal
import subprocess
import sys
import time
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import aio_pika
import chess
import chess.engine
import pytest
import pytest_asyncio
from aio_pika.abc import AbstractChannel, AbstractQueue
from fastapi.testclient import TestClient

from analysis.contracts import AnalysisResult
from analysis.main import app
from analysis.worker import Settings, Worker
from tests import topology
from tests.conftest import FIXTURES, STOCKFISH, TEST_DEPTH

SRC = Path(__file__).resolve().parents[1] / "src"

JOB = "0192c7a4-5b1e-7c3a-9f00-2d1c8e7b6a01"


@pytest_asyncio.fixture
async def broker(amqp_url) -> AsyncIterator[AbstractChannel]:
    connection = await aio_pika.connect_robust(amqp_url)
    async with connection:
        channel = await connection.channel()
        await topology.declare(channel)
        yield channel


@contextlib.asynccontextmanager
async def running_worker(amqp_url: str, **settings) -> AsyncIterator[Worker]:
    defaults = Settings(
        amqp_url=amqp_url, stockfish_path=STOCKFISH, engine_depth=TEST_DEPTH, engine_position_timeout=2
    )
    worker = Worker(dataclasses.replace(defaults, **settings))
    task = asyncio.create_task(worker.run())
    for _ in range(100):
        if worker.ready:
            break
        await asyncio.sleep(0.1)
    assert worker.ready, "worker never started consuming"
    try:
        yield worker
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest_asyncio.fixture
async def worker(amqp_url, broker) -> AsyncIterator[Worker]:
    async with running_worker(amqp_url) as worker:
        yield worker


def request_body(**overrides) -> bytes:
    body = json.loads((FIXTURES / "request.scholars-mate.json").read_text())
    body["jobId"] = str(uuid.uuid4())
    body["deadline"] = (datetime.now(UTC) + timedelta(minutes=3)).isoformat()
    body.update(overrides)
    return json.dumps(body).encode()


async def send(channel: AbstractChannel, body: bytes) -> None:
    exchange = await channel.get_exchange("analysis")
    await exchange.publish(aio_pika.Message(body, content_type="application/json"), routing_key="request")


async def receive(channel: AbstractChannel, queue_name: str) -> aio_pika.abc.AbstractIncomingMessage:
    queue: AbstractQueue = await channel.get_queue(queue_name)
    async with asyncio.timeout(30):
        while True:
            if message := await queue.get(fail=False):
                await message.ack()
                return message
            await asyncio.sleep(0.1)


async def test_request_is_analyzed_and_answered(broker, worker):
    await send(broker, request_body(jobId=JOB))

    message = await receive(broker, "analysis.results")

    result = AnalysisResult.model_validate_json(message.body)
    assert str(result.job_id) == JOB
    assert result.status == "COMPLETED"
    assert result.moves is not None
    assert len(result.moves) == 7
    assert message.message_id == JOB
    assert message.correlation_id == JOB
    assert message.delivery_mode == aio_pika.DeliveryMode.PERSISTENT


async def test_invalid_pgn_gets_a_failed_result(broker, worker):
    await send(broker, request_body(jobId=JOB, pgn="1. e4 e5 2. Nf9"))

    result = AnalysisResult.model_validate_json((await receive(broker, "analysis.results")).body)

    assert result.status == "FAILED"
    assert result.error is not None
    assert result.error.code == "INVALID_PGN"


async def test_unknown_schema_version_gets_a_failed_result(broker, worker):
    await send(broker, request_body(jobId=JOB, schemaVersion=99))

    result = AnalysisResult.model_validate_json((await receive(broker, "analysis.results")).body)

    assert result.error is not None
    assert result.error.code == "UNSUPPORTED_SCHEMA_VERSION"


@pytest.mark.parametrize("body", [b"not json", b'{"schemaVersion": 1}', b'{"jobId": "x", "matchId": "y"}'])
async def test_unreadable_request_is_dead_lettered(broker, worker, body):
    await send(broker, body)

    message = await receive(broker, "analysis.requests.dlq")

    assert message.body == body


async def test_request_with_a_passed_deadline_is_dropped(broker, worker):
    expired = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
    await send(broker, request_body(jobId=JOB, deadline=expired))
    # A request behind it proves the worker moved on without answering the first
    await send(broker, request_body(jobId=str(uuid.uuid4())))

    result = AnalysisResult.model_validate_json((await receive(broker, "analysis.results")).body)

    assert str(result.job_id) != JOB
    results = await broker.get_queue("analysis.results")
    assert (await results.get(fail=False)) is None


def test_http_health_and_readiness(amqp_url, monkeypatch):
    async def declare() -> None:
        connection = await aio_pika.connect_robust(amqp_url)
        async with connection:
            await topology.declare(await connection.channel())

    asyncio.run(declare())
    monkeypatch.setenv("AMQP_URL", amqp_url)
    monkeypatch.setenv("STOCKFISH_PATH", STOCKFISH)

    with TestClient(app) as client:
        assert client.get("/healthz").json() == {"status": "ok"}
        for _ in range(100):
            if client.get("/readyz").status_code == 200:
                break
            time.sleep(0.1)
        assert client.get("/readyz").json() == {"ready": True}


async def test_a_crashed_engine_is_restarted_and_the_job_retried(broker, worker):
    assert worker._engine_transport is not None
    worker._engine_transport.kill()

    await send(broker, request_body(jobId=JOB))
    result = AnalysisResult.model_validate_json((await receive(broker, "analysis.results")).body)

    assert result.status == "COMPLETED"
    assert await worker.is_ready()


@pytest.mark.parametrize("failure", [chess.engine.EngineTerminatedError("crashed"), TimeoutError()])
async def test_an_engine_that_fails_twice_gives_an_engine_error(broker, worker, monkeypatch, failure):
    attempts = 0

    async def failing(*args, **kwargs):
        nonlocal attempts
        attempts += 1
        raise failure

    monkeypatch.setattr("analysis.worker.analyze", failing)
    await send(broker, request_body(jobId=JOB))

    result = AnalysisResult.model_validate_json((await receive(broker, "analysis.results")).body)

    assert result.error is not None
    assert result.error.code == "ENGINE_ERROR"
    assert attempts == 2


async def test_a_job_over_its_budget_fails_and_leaves_a_working_engine(amqp_url, broker):
    # Depth 40 without a time cap takes far longer than the budget
    async with running_worker(
        amqp_url, engine_depth=40, engine_position_timeout=0, job_budget_seconds=0.5
    ) as worker:
        await send(broker, request_body(jobId=JOB))

        result = AnalysisResult.model_validate_json((await receive(broker, "analysis.results")).body)

        assert result.error is not None
        assert result.error.code == "BUDGET_EXCEEDED"
        assert worker._engine is not None
        async with asyncio.timeout(10):
            info = await worker._engine.analyse(chess.Board(), chess.engine.Limit(depth=5))
        assert "score" in info


def start_service(amqp_url: str, depth: int) -> subprocess.Popen:
    """The worker as it runs in its container: uvicorn in its own process, with no time cap per position."""
    env = os.environ | {
        "AMQP_URL": amqp_url,
        "STOCKFISH_PATH": STOCKFISH,
        "ENGINE_DEPTH": str(depth),
        "ENGINE_POSITION_TIMEOUT": "0",
        "PYTHONPATH": str(SRC),
    }
    return subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "analysis.main:app", "--port", "0"],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


async def request_queue(channel: AbstractChannel) -> tuple[int, int]:
    """(ready messages, consumers) of the request queue."""
    # On the underlying channel: aio-pika keeps the first answer of a passive declare
    ok = await (await channel.get_underlay_channel()).queue_declare("analysis.requests", passive=True)
    return ok.message_count or 0, ok.consumer_count or 0


async def wait_for(condition, seconds: float = 30) -> None:
    async with asyncio.timeout(seconds):
        while True:
            if await condition():
                return
            await asyncio.sleep(0.1)


async def test_a_job_that_keeps_killing_its_worker_ends_up_in_the_dead_letter_queue(broker, amqp_url):
    await send(broker, request_body(jobId=JOB))
    dlq = await broker.get_queue("analysis.requests.dlq")
    kills = 0
    dead_letter = None
    while dead_letter is None and kills < 6:
        service = start_service(amqp_url, depth=40)
        try:
            # Taken by the worker, which is now deep in a search
            await wait_for(lambda: _delivered(broker))
        finally:
            service.kill()
            service.wait()
        kills += 1
        await wait_for(lambda: _no_consumers(broker))
        await asyncio.sleep(0.3)
        dead_letter = await dlq.get(fail=False)

    # x-delivery-limit=3 allows three redeliveries: the fourth worker that dies sends it to the DLQ
    assert dead_letter is not None
    assert kills == 4
    assert json.loads(dead_letter.body)["jobId"] == JOB
    results = await broker.get_queue("analysis.results")
    assert (await results.get(fail=False)) is None


async def _delivered(channel: AbstractChannel) -> bool:
    ready, consumers = await request_queue(channel)
    return ready == 0 and consumers > 0


async def _no_consumers(channel: AbstractChannel) -> bool:
    return (await request_queue(channel))[1] == 0


async def test_sigterm_lets_the_job_in_progress_finish(broker, amqp_url):
    service = start_service(amqp_url, depth=16)
    try:
        await wait_for(lambda: _consuming(broker))
        await send(broker, request_body(jobId=JOB))
        await wait_for(lambda: _delivered(broker))

        service.send_signal(signal.SIGTERM)

        result = AnalysisResult.model_validate_json((await receive(broker, "analysis.results")).body)
        assert result.status == "COMPLETED"
        # uvicorn shuts down cleanly, then re-raises the signal it caught
        assert await asyncio.to_thread(service.wait, 30) == -signal.SIGTERM
        ready, consumers = await request_queue(broker)
        assert (ready, consumers) == (0, 0)  # acked, not returned
    finally:
        service.kill()
        service.wait()


async def _consuming(channel: AbstractChannel) -> bool:
    return (await request_queue(channel))[1] > 0
