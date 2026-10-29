"""Consume analysis requests, analyze them with Stockfish and publish the results.

Spring owns the topology (exchanges, queues, dead-lettering); this worker only declares passively, so it
can never create a queue with different arguments.
"""

import asyncio
import contextlib
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

from analysis.contracts import SCHEMA_VERSION, AnalysisRequest, AnalysisResult, ErrorCode, ErrorInfo
from analysis.engine import AnalysisError, Analyzed, analyze
from analysis.llm import Explainer

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
    # Seconds a job may take, unless its deadline comes first
    job_budget_seconds: float = field(
        default_factory=lambda: float(os.environ.get("JOB_BUDGET_SECONDS", "120"))
    )
    # Any OpenAI-compatible endpoint; the LLM is off unless both the URL and the model are set
    llm_base_url: str = field(default_factory=lambda: os.environ.get("LLM_BASE_URL", ""))
    llm_model: str = field(default_factory=lambda: os.environ.get("LLM_MODEL", ""))
    llm_api_key: str = field(default_factory=lambda: os.environ.get("LLM_API_KEY", ""))


class Worker:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.ready = False
        self._engine: chess.engine.UciProtocol | None = None
        self._engine_transport: asyncio.SubprocessTransport | None = None
        self._connection: AbstractRobustConnection | None = None
        self._queue: aio_pika.abc.AbstractQueue | None = None
        self._consumer_tag: str | None = None
        # Held while a job runs, so shutdown can wait for it
        self._job = asyncio.Lock()
        self.explainer = (
            Explainer(settings.llm_base_url, settings.llm_model, settings.llm_api_key)
            if settings.llm_base_url and settings.llm_model
            else None
        )

    async def _start_engine(self) -> None:
        transport, engine = await chess.engine.popen_uci(self.settings.stockfish_path)
        await engine.configure({"Threads": 1, "Hash": 64})
        self._engine = engine
        self._engine_transport = transport

    async def _restart_engine(self) -> None:
        """Replace the engine: after a crash, hang or cancelled command, python-chess cannot use it again."""
        if self._engine_transport is not None:
            self._engine_transport.close()  # kills the process if it is still running
        self._engine = self._engine_transport = None
        await self._start_engine()

    async def run(self) -> None:
        """Serve until cancelled; reconnect to the broker and wait for Spring to declare the queue."""
        await self._start_engine()
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
            if self._engine is not None:
                with contextlib.suppress(Exception):
                    async with asyncio.timeout(2):
                        await self._engine.quit()
            if self._engine_transport is not None:
                self._engine_transport.close()

    async def shutdown(self, grace: float) -> None:
        """Stop taking jobs and give the one in progress `grace` seconds to finish; then cancel `run`.

        A job still running after that goes back to the queue when the connection closes, and RabbitMQ counts
        that as a delivery, as it does when the worker dies.
        """
        self.ready = False
        if self._queue is not None and self._consumer_tag is not None:
            try:
                await self._queue.cancel(self._consumer_tag)
            except Exception as e:  # noqa: BLE001 - the channel may already be gone
                log.info("Could not cancel the consumer: %s", e)
        try:
            async with asyncio.timeout(grace):
                async with self._job:
                    pass
        except TimeoutError:
            log.warning("A job is still running after %ss; it will be redelivered", grace)

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

            self._queue = queue
            self._consumer_tag = await queue.consume(on_message)
            self.ready = True
            log.info("Consuming %s", REQUEST_QUEUE)
            await asyncio.Event().wait()

    async def is_ready(self) -> bool:
        if not self.ready or self._engine is None or self._connection is None or self._connection.is_closed:
            return False
        # Not a UCI ping: the protocol runs one command at a time, so a probe during a job times out, and the
        # cancelled ping leaves python-chess's state corrupted and the job hung
        return self._engine_transport is not None and self._engine_transport.get_returncode() is None

    async def handle(
        self,
        message: AbstractIncomingMessage,
        channel: AbstractRobustChannel,
        exchange: aio_pika.abc.AbstractExchange,
    ) -> None:
        async with self._job:
            await self._handle(message, exchange)

    async def _handle(
        self, message: AbstractIncomingMessage, exchange: aio_pika.abc.AbstractExchange
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
        budget = min((request.deadline - datetime.now(UTC)).total_seconds(), self.settings.job_budget_seconds)
        try:
            async with asyncio.timeout(budget):
                analyzed = await self._analyze_with_retry(request)
        except TimeoutError:
            log.warning("Job %s exceeded its %.0fs budget", request.job_id, budget)
            await self._restart_engine()  # its command was cancelled mid-search
            return self._failed(request, "BUDGET_EXCEEDED", f"not finished within {budget:.0f}s")
        except AnalysisError as e:
            log.info("Job %s failed: %s", request.job_id, e.message)
            return self._failed(request, e.code, e.message)
        summary, moves = analyzed.summary, analyzed.moves
        # After the engine step, so the job budget and the hang handling stay the engine's alone
        if self.explainer is not None:
            seconds_left = (request.deadline - datetime.now(UTC)).total_seconds()
            explained = await self.explainer.explain(analyzed, seconds_left)
            if explained is not None:
                summary = explained.summary
                moves = [
                    m.model_copy(update={"explanation": explained.explanations.get(m.ply)}) for m in moves
                ]
        return AnalysisResult(
            job_id=request.job_id,
            match_id=request.match_id,
            status="COMPLETED",
            engine=analyzed.engine,
            initial_eval=analyzed.initial_eval,
            moves=moves,
            summary=summary,
        )

    async def _analyze_with_retry(self, request: AnalysisRequest) -> Analyzed:
        """Analyze, and if the engine crashes or hangs, once more on a new engine."""
        for attempt in (1, 2):
            if self._engine is None:
                await self._start_engine()  # a failed restart left none
            assert self._engine is not None
            try:
                return await analyze(
                    self._engine,
                    request.pgn,
                    self.settings.engine_depth,
                    self.settings.engine_position_timeout,
                    game_token=request.job_id,
                )
            except (chess.engine.EngineError, TimeoutError) as e:
                # TimeoutError here is the per-position hang guard; the job budget arrives as a cancellation
                log.warning("Engine failed on job %s (attempt %d): %r", request.job_id, attempt, e)
                await self._restart_engine()
        raise AnalysisError("ENGINE_ERROR", "the engine crashed or hung twice")

    @staticmethod
    def _failed(request: AnalysisRequest, code: ErrorCode, message: str) -> AnalysisResult:
        return AnalysisResult(
            job_id=request.job_id,
            match_id=request.match_id,
            status="FAILED",
            error=ErrorInfo(code=code, message=message),
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
