import os
from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import chess.engine
import pytest
import pytest_asyncio

from analysis.worker import default_stockfish_path

STOCKFISH = default_stockfish_path()
FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "analysis" / "v1"
# Shallow depth keeps tests fast; they assert robust properties, not exact centipawns
TEST_DEPTH = 8


@pytest.fixture(scope="session")
def amqp_url() -> Iterator[str]:
    """A broker URL: AMQP_TEST_URL if set (CI), else a RabbitMQ container."""
    if url := os.environ.get("AMQP_TEST_URL"):
        yield url
        return
    from testcontainers.community.rabbitmq import RabbitMqContainer

    with RabbitMqContainer("rabbitmq:4.1-management") as rabbit:
        yield f"amqp://guest:guest@{rabbit.get_container_host_ip()}:{rabbit.get_exposed_port(5672)}/"


@pytest_asyncio.fixture
async def engine() -> AsyncIterator[chess.engine.UciProtocol]:
    _, engine = await chess.engine.popen_uci(STOCKFISH)
    await engine.configure({"Threads": 1, "Hash": 16})
    yield engine
    await engine.quit()
