"""The queue topology Spring declares in production, repeated here so worker tests can run without Spring.

Keep in sync with docs/specs/ai-analysis.md section 4.1.
"""

from typing import Any

import aio_pika
from aio_pika.abc import AbstractChannel

QUEUES = ["analysis.requests", "analysis.results", "analysis.requests.dlq", "analysis.results.dlq"]


async def declare(channel: AbstractChannel) -> None:
    for name in QUEUES:
        await channel.queue_delete(name)
    exchange = await channel.declare_exchange("analysis", aio_pika.ExchangeType.DIRECT, durable=True)
    dlx = await channel.declare_exchange("analysis.dlx", aio_pika.ExchangeType.DIRECT, durable=True)

    def quorum(dlk: str, limit: int, **extra: Any) -> dict[str, Any]:
        return {
            "x-queue-type": "quorum",
            "x-dead-letter-exchange": "analysis.dlx",
            "x-dead-letter-routing-key": dlk,
            "x-delivery-limit": limit,
            **extra,
        }

    requests = await channel.declare_queue(
        "analysis.requests",
        durable=True,
        arguments=quorum("request", 3, **{"x-max-length": 1000, "x-overflow": "reject-publish"}),
    )
    results = await channel.declare_queue("analysis.results", durable=True, arguments=quorum("result", 5))
    await requests.bind(exchange, "request")
    await results.bind(exchange, "result")
    for name, key in (("analysis.requests.dlq", "request"), ("analysis.results.dlq", "result")):
        dlq = await channel.declare_queue(name, durable=True, arguments={"x-queue-type": "quorum"})
        await dlq.bind(dlx, key)
