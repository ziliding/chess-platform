"""Consume analysis requests, analyze them with Stockfish and publish the results.

Spring owns the topology (exchanges, queues, dead-lettering); this worker only declares passively, so it
can never create a queue with different arguments.
"""

import asyncio
import json
import logging
from datetime import UTC, datetime
from uuid import UUID

import aio_pika
from aio_pika.abc import AbstractIncomingMessage, AbstractRobustChannel, AbstractRobustConnection
from pydantic import ValidationError

from analysis.contracts import SCHEMA_VERSION, AnalysisRequest, AnalysisResult, ErrorInfo
from analysis.jobs import JobRunner, Settings

log = logging.getLogger(__name__)

EXCHANGE = "analysis"
REQUEST_QUEUE = "analysis.requests"
RESULT_KEY = "result"


class Worker:
    """The broker adapter: checks a request's envelope, runs it as a job, publishes the result, then acks."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.ready = False
        self._runner = JobRunner(settings)
        self._connection: AbstractRobustConnection | None = None
        self._queue: aio_pika.abc.AbstractQueue | None = None
        self._consumer_tag: str | None = None
        # Held while a job runs, so shutdown can wait for it
        self._job = asyncio.Lock()

    async def run(self) -> None:
        """Serve until cancelled; reconnect to the broker and wait for Spring to declare the queue."""
        async with self._runner:
            try:
                await self._serve()
            finally:
                self.ready = False

    async def _serve(self) -> None:
        while True:
            try:
                await self._consume()
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001 - keep serving whatever the broker does
                log.warning("Consumer stopped (%s), retrying in 2s", e)
                self.ready = False
                await asyncio.sleep(2)

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
        if not self.ready or self._connection is None or self._connection.is_closed:
            return False
        return self._runner.alive()

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
            result = (await self._runner.run(request)).result
        except Exception:
            # Unexpected: leave it for redelivery; RabbitMQ dead-letters it after the delivery limit
            log.exception("Job %s failed unexpectedly", job_id)
            await message.nack(requeue=True)
            return

        # Ack only once the broker has confirmed the result
        await self._publish(exchange, result)
        await message.ack()

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
