import asyncio
import json
import time
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import aio_pika
import pytest
import pytest_asyncio
from aio_pika.abc import AbstractChannel, AbstractQueue
from fastapi.testclient import TestClient

from analysis.contracts import AnalysisResult
from analysis.main import app
from analysis.worker import Settings, Worker
from tests import topology
from tests.conftest import FIXTURES, STOCKFISH, TEST_DEPTH

JOB = "0192c7a4-5b1e-7c3a-9f00-2d1c8e7b6a01"


@pytest_asyncio.fixture
async def broker(amqp_url) -> AsyncIterator[AbstractChannel]:
    connection = await aio_pika.connect_robust(amqp_url)
    async with connection:
        channel = await connection.channel()
        await topology.declare(channel)
        yield channel


@pytest_asyncio.fixture
async def worker(amqp_url, broker) -> AsyncIterator[Worker]:
    worker = Worker(
        Settings(
            amqp_url=amqp_url, stockfish_path=STOCKFISH, engine_depth=TEST_DEPTH, engine_position_timeout=2
        )
    )
    task = asyncio.create_task(worker.run())
    for _ in range(100):
        if worker.ready:
            break
        await asyncio.sleep(0.1)
    assert worker.ready, "worker never started consuming"
    yield worker
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)


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
