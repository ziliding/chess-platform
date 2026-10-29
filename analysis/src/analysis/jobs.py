"""Run analysis jobs: the engine session, the job budget, one retry on a new engine, and the error codes.

The broker adapter (`analysis.worker`) and the eval both run jobs through `JobRunner`, so they share every
rule here, including the engine's settings.
"""

import asyncio
import contextlib
import logging
import os
import shutil
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from types import TracebackType
from typing import Self

import chess.engine

from analysis.contracts import AnalysisRequest, AnalysisResult, ErrorCode, ErrorInfo
from analysis.engine import AnalysisError, Analyzed, analyze
from analysis.llm import Attempt, Explainer

log = logging.getLogger(__name__)

ENGINE_OPTIONS = {"Threads": 1, "Hash": 64}


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


async def open_engine(path: str) -> tuple[asyncio.SubprocessTransport, chess.engine.UciProtocol]:
    """Start Stockfish with the settings every job uses."""
    transport, engine = await chess.engine.popen_uci(path)
    await engine.configure(ENGINE_OPTIONS)
    return transport, engine


@dataclass(frozen=True)
class JobRun:
    result: AnalysisResult
    engine_seconds: float  # the engine step, budget handling included
    llm: Attempt | None = None  # None without an LLM, or when its breaker skipped the step


class JobRunner:
    """Owns one Stockfish process and runs one job at a time on it. Use it as an async context manager."""

    def __init__(self, settings: Settings, explainer: Explainer | None = None):
        self.settings = settings
        if explainer is None and settings.llm_base_url and settings.llm_model:
            explainer = Explainer(settings.llm_base_url, settings.llm_model, settings.llm_api_key)
        self._explainer = explainer
        self._engine: chess.engine.UciProtocol | None = None
        self._transport: asyncio.SubprocessTransport | None = None

    @property
    def explains(self) -> bool:
        """Whether jobs get an LLM summary and explanations."""
        return self._explainer is not None

    async def __aenter__(self) -> Self:
        await self._start()
        return self

    async def __aexit__(
        self, exc_type: type[BaseException] | None, exc: BaseException | None, tb: TracebackType | None
    ) -> None:
        await self.close()

    async def close(self) -> None:
        if self._engine is not None:
            with contextlib.suppress(Exception):
                async with asyncio.timeout(2):
                    await self._engine.quit()
        self._stop()

    def alive(self) -> bool:
        # Not a UCI ping: the protocol runs one command at a time, so a probe during a job times out, and the
        # cancelled ping leaves python-chess's state corrupted and the job hung
        return self._transport is not None and self._transport.get_returncode() is None

    async def run(self, request: AnalysisRequest) -> JobRun:
        """Analyze the request's game. Never raises for a job that failed: the result says why."""
        start = time.perf_counter()
        budget = min((request.deadline - datetime.now(UTC)).total_seconds(), self.settings.job_budget_seconds)
        try:
            async with asyncio.timeout(budget):
                analyzed = await self._analyze_with_retry(request)
        except TimeoutError:
            log.warning("Job %s exceeded its %.0fs budget", request.job_id, budget)
            await self._restart()  # its command was cancelled mid-search
            failed = _failed(request, "BUDGET_EXCEEDED", f"not finished within {budget:.0f}s")
            return JobRun(failed, time.perf_counter() - start)
        except AnalysisError as e:
            log.info("Job %s failed: %s", request.job_id, e.message)
            return JobRun(_failed(request, e.code, e.message), time.perf_counter() - start)
        engine_seconds = time.perf_counter() - start

        summary, moves, attempt = analyzed.summary, analyzed.moves, None
        # After the engine step, so the job budget and the hang handling stay the engine's alone
        if self._explainer is not None:
            seconds_left = (request.deadline - datetime.now(UTC)).total_seconds()
            explained = await self._explainer.explain(analyzed, seconds_left)
            attempt = self._explainer.last
            if explained is not None:
                summary = explained.summary
                moves = [
                    m.model_copy(update={"explanation": explained.explanations.get(m.ply)}) for m in moves
                ]
        result = AnalysisResult(
            job_id=request.job_id,
            match_id=request.match_id,
            status="COMPLETED",
            engine=analyzed.engine,
            initial_eval=analyzed.initial_eval,
            moves=moves,
            summary=summary,
        )
        return JobRun(result, engine_seconds, attempt)

    async def _analyze_with_retry(self, request: AnalysisRequest) -> Analyzed:
        """Analyze, and if the engine crashes or hangs, once more on a new engine."""
        for attempt in (1, 2):
            if self._engine is None:
                await self._start()  # a failed restart left none
            assert self._engine is not None
            try:
                return await analyze(
                    self._engine,
                    request.pgn,
                    self.settings.engine_depth,
                    self.settings.engine_position_timeout,
                    # A new game per job, so the engine's hash from earlier jobs cannot change the results
                    game_token=request.job_id,
                )
            except (chess.engine.EngineError, TimeoutError) as e:
                # TimeoutError here is the per-position hang guard; the job budget arrives as a cancellation
                log.warning("Engine failed on job %s (attempt %d): %r", request.job_id, attempt, e)
                await self._restart()
        raise AnalysisError("ENGINE_ERROR", "the engine crashed or hung twice")

    async def _start(self) -> None:
        self._transport, self._engine = await open_engine(self.settings.stockfish_path)

    async def _restart(self) -> None:
        """Replace the engine: after a crash, hang or cancelled command, python-chess cannot use it again."""
        self._stop()
        await self._start()

    def _stop(self) -> None:
        if self._transport is not None:
            self._transport.close()  # kills the process if it is still running
        self._engine = self._transport = None


def _failed(request: AnalysisRequest, code: ErrorCode, message: str) -> AnalysisResult:
    return AnalysisResult(
        job_id=request.job_id,
        match_id=request.match_id,
        status="FAILED",
        error=ErrorInfo(code=code, message=message),
    )
