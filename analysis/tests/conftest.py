import os
from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import chess.engine
import pytest
import pytest_asyncio

from analysis.jobs import default_stockfish_path, open_engine

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
    from testcontainers.core.container import DockerContainer
    from testcontainers.core.wait_strategies import LogMessageWaitStrategy

    # Not `guest`: RabbitMQ only lets it connect from the broker's own host
    rabbit = (
        DockerContainer("rabbitmq:4.1-management")
        .with_env("RABBITMQ_DEFAULT_USER", "chess")
        .with_env("RABBITMQ_DEFAULT_PASS", "password")
        .with_exposed_ports(5672)
        .waiting_for(LogMessageWaitStrategy("Server startup complete"))
    )
    with rabbit:
        yield f"amqp://chess:password@{rabbit.get_container_host_ip()}:{rabbit.get_exposed_port(5672)}/"


@pytest_asyncio.fixture
async def engine() -> AsyncIterator[chess.engine.UciProtocol]:
    # The production engine settings, so tests and the service cannot drift apart
    transport, engine = await open_engine(STOCKFISH)
    yield engine
    await engine.quit()
    transport.close()
