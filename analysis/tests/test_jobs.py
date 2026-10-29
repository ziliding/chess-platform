"""The job rules without a broker: the engine retry, the budget, and the engine's lifetime."""

import dataclasses
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import chess.engine
import pytest
import pytest_asyncio

from analysis import engine
from analysis.contracts import SCHEMA_VERSION, AnalysisRequest
from analysis.jobs import JobRunner, Settings
from tests.conftest import STOCKFISH, TEST_DEPTH

SCHOLARS_MATE = "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0"
SETTINGS = Settings(stockfish_path=STOCKFISH, engine_depth=TEST_DEPTH, engine_position_timeout=2)


def request(pgn: str = SCHOLARS_MATE) -> AnalysisRequest:
    return AnalysisRequest(
        schema_version=SCHEMA_VERSION,
        job_id=uuid4(),
        match_id=uuid4(),
        pgn=pgn,
        deadline=datetime.now(UTC) + timedelta(minutes=3),
    )


@pytest_asyncio.fixture
async def runner() -> AsyncIterator[JobRunner]:
    async with JobRunner(SETTINGS) as runner:
        yield runner


async def test_a_job_is_analyzed(runner):
    run = await runner.run(request())

    assert run.result.status == "COMPLETED"
    assert run.result.moves is not None and len(run.result.moves) == 7
    assert run.engine_seconds > 0
    assert run.llm is None
    assert runner.alive()


async def test_a_game_that_cannot_be_analyzed_fails_with_its_code(runner):
    run = await runner.run(request("1. e4 e5 2. Nf9"))

    assert run.result.error is not None
    assert run.result.error.code == "INVALID_PGN"


async def test_an_engine_that_crashes_once_is_replaced_and_the_job_retried(runner, monkeypatch):
    attempts = 0

    async def crashes_once(*args, **kwargs):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise chess.engine.EngineTerminatedError("crashed")
        return await real(*args, **kwargs)

    real = engine.analyze
    monkeypatch.setattr("analysis.jobs.analyze", crashes_once)

    run = await runner.run(request())

    assert run.result.status == "COMPLETED"
    assert attempts == 2
    assert runner.alive()


@pytest.mark.parametrize("failure", [chess.engine.EngineTerminatedError("crashed"), TimeoutError()])
async def test_an_engine_that_fails_twice_gives_an_engine_error(runner, monkeypatch, failure):
    attempts = 0

    async def failing(*args, **kwargs):
        nonlocal attempts
        attempts += 1
        raise failure

    monkeypatch.setattr("analysis.jobs.analyze", failing)

    run = await runner.run(request())

    assert run.result.error is not None
    assert run.result.error.code == "ENGINE_ERROR"
    assert attempts == 2
    assert runner.alive()


async def test_a_job_over_its_budget_fails_and_leaves_a_working_engine():
    # Depth 40 without a time cap takes far longer than the budget
    slow = dataclasses.replace(SETTINGS, engine_depth=40, engine_position_timeout=0, job_budget_seconds=0.5)
    async with JobRunner(slow) as runner:
        run = await runner.run(request())

        assert run.result.error is not None
        assert run.result.error.code == "BUDGET_EXCEEDED"
        runner.settings = SETTINGS
        assert (await runner.run(request())).result.status == "COMPLETED"


async def test_a_closed_runner_is_not_alive():
    runner = JobRunner(SETTINGS)
    assert not runner.alive()
    async with runner:
        assert runner.alive()
    assert not runner.alive()
