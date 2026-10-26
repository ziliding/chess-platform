"""Consume analysis requests, analyze them with Stockfish and publish the results.

Spring owns the topology (exchanges, queues, dead-lettering); this worker only declares passively, so it
can never create a queue with different arguments.
"""

import asyncio
import json
import logging
import os
import shutil
from dataclasses import dataclass, field
from datetime import UTC, datetime
from uuid import UUID

import aio_pika
import chess.engine
from aio_pika.abc import AbstractIncomingMessage, AbstractRobustChannel, AbstractRobustConnection
from pydantic import ValidationError

from analysis.contracts import SCHEMA_VERSION, AnalysisRequest, AnalysisResult, ErrorInfo
from analysis.engine import AnalysisError, analyze

log = logging.getLogger(__name__)

EXCHANGE = "analysis"
REQUEST_QUEUE = "analysis.requests"
RESULT_KEY = "result"


def default_stockfish_path() -> str:
    """STOCKFISH_PATH, else `stockfish` on PATH (Homebrew), else Debian's location (the image)."""
    return os.environ.get("STOCKFISH_PATH") or shutil.which("stockfish") or "/usr/games/stockfish"


@dataclass(frozen=True)
class Settings:
    amqp_url: str = field(
        default_factory=lambda: os.environ.get("AMQP_URL", "amqp://chess:password@localhost/")
    )
    stockfish_path: str = field(default_factory=default_stockfish_path)
    engine_depth: int = field(default_factory=lambda: int(os.environ.get("ENGINE_DEPTH", "16")))
    # Seconds per position; 0 disables the cap (depth only, deterministic)
    engine_position_timeout: float = field(
        default_factory=lambda: float(os.environ.get("ENGINE_POSITION_TIMEOUT", "2"))
    )


class Worker:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.ready = False
        self._engine: chess.engine.UciProtocol | None = None
        self._connection: AbstractRobustConnection | None = None

    async def run(self) -> None:
        """Serve until cancelled; reconnect to the broker and wait for Spring to declare the queue."""
        transport, engine = await chess.engine.popen_uci(self.settings.stockfish_path)
        await engine.configure({"Threads": 1, "Hash": 64})
        self._engine = engine
        try:
            while True:
                try:
                    await self._consume()
                except asyncio.CancelledError:
                    raise
                except Exception as e:  # noqa: BLE001 - keep serving whatever the broker does
                    log.warning("Consumer stopped (%s), retrying in 2s", e)
                    self.ready = False
                    await asyncio.sleep(2)
        finally:
            self.ready = False
            await engine.quit()
            transport.close()

    async def _consume(self) -> None:
        connection = await aio_pika.connect_robust(self.settings.amqp_url)
        self._connection = connection
        async with connection:
            channel = await connection.channel(publisher_confirms=True)
            await channel.set_qos(prefetch_count=1)
            # Passive: fails until Spring has declared the queue
            queue = await channel.get_queue(REQUEST_QUEUE, ensure=True)
            exchange = await channel.get_exchange(EXCHANGE, ensure=True)

            async def on_message(message: AbstractIncomingMessage) -> None:
                await self.handle(message, channel, exchange)

            await queue.consume(on_message)
            self.ready = True
            log.info("Consuming %s", REQUEST_QUEUE)
            await asyncio.Event().wait()

    async def is_ready(self) -> bool:
        if not self.ready or self._engine is None or self._connection is None or self._connection.is_closed:
            return False
        try:
            await asyncio.wait_for(self._engine.ping(), timeout=2)
        except Exception:  # noqa: BLE001
            return False
        return True

    async def handle(
        self,
        message: AbstractIncomingMessage,
        channel: AbstractRobustChannel,
        exchange: aio_pika.abc.AbstractExchange,
    ) -> None:
        try:
            raw = json.loads(message.body)
            job_id, match_id = UUID(raw["jobId"]), UUID(raw["matchId"])
        except ValueError, KeyError, TypeError:
            log.error("Dead-lettering a request without a readable jobId/matchId")
            await message.reject(requeue=False)
            return

        if raw.get("schemaVersion") != SCHEMA_VERSION:
            error = ErrorInfo(
                code="UNSUPPORTED_SCHEMA_VERSION", message=f"schemaVersion {raw.get('schemaVersion')}"
            )
            await self._publish(
                exchange, AnalysisResult(job_id=job_id, match_id=match_id, status="FAILED", error=error)
            )
            await message.ack()
            return

        try:
            request = AnalysisRequest.model_validate(raw)
        except ValidationError as e:
            log.error("Dead-lettering invalid request %s: %s", job_id, e)
            await message.reject(requeue=False)
            return

        if request.deadline <= datetime.now(UTC):
            log.info("Dropping job %s: deadline %s has passed", job_id, request.deadline)
            await message.ack()
            return

        try:
            result = await self._analyze(request)
        except Exception:
            # Unexpected: leave it for redelivery; RabbitMQ dead-letters it after the delivery limit
            log.exception("Job %s failed unexpectedly", job_id)
            await message.nack(requeue=True)
            return

        # Ack only once the broker has confirmed the result
        await self._publish(exchange, result)
        await message.ack()

    async def _analyze(self, request: AnalysisRequest) -> AnalysisResult:
        assert self._engine is not None
        try:
            analyzed = await analyze(
                self._engine,
                request.pgn,
                self.settings.engine_depth,
                self.settings.engine_position_timeout,
                game_token=request.job_id,
            )
        except AnalysisError as e:
            log.info("Job %s is permanently invalid: %s", request.job_id, e.message)
            return AnalysisResult(
                job_id=request.job_id,
                match_id=request.match_id,
                status="FAILED",
                error=ErrorInfo(code=e.code, message=e.message),
            )
        return AnalysisResult(
            job_id=request.job_id,
            match_id=request.match_id,
            status="COMPLETED",
            engine=analyzed.engine,
            initial_eval=analyzed.initial_eval,
            moves=analyzed.moves,
            summary=analyzed.summary,
        )

    @staticmethod
    async def _publish(exchange: aio_pika.abc.AbstractExchange, result: AnalysisResult) -> None:
        job_id = str(result.job_id)
        await exchange.publish(
            aio_pika.Message(
                result.to_json(),
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                message_id=job_id,
                correlation_id=job_id,
            ),
            routing_key=RESULT_KEY,
            mandatory=True,
        )
