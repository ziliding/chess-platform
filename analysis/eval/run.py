"""Engine eval (spec §12.2): analyze `eval/games` through the worker's job path and score E1-E6.

The `lichess-*.pgn` games come from the Lichess database (CC0), exported with Lichess's own computer
analysis: a `[%eval]` after every move and a judgment glyph (?!, ?, ??) on every inaccuracy, mistake and
blunder. Those are the reference labels. The `platform-*.pgn` games come from the Java formatter; they only
test format compatibility and completion.

From `analysis/`: `uv run python -m eval.run`. It writes `eval/report.json` and `eval/report.md` and exits 1
if any metric fails. The run is depth-only (`ENGINE_POSITION_TIMEOUT=0`), so it is deterministic.
"""

import asyncio
import io
import json
import statistics
import sys
import time
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import chess.pgn

from analysis.contracts import (
    SCHEMA_VERSION,
    AnalysisRequest,
    AnalysisResult,
    Classification,
    Eval,
    MoveAnalysis,
)
from analysis.engine import fmt_eval
from analysis.worker import Settings, Worker

EVAL_DIR = Path(__file__).parent
GAMES = EVAL_DIR / "games"
SERIOUS = ("MISTAKE", "BLUNDER")
# Lichess's judgment glyphs, as python-chess reads them
NAG_LABELS: dict[int, Classification] = {
    chess.pgn.NAG_DUBIOUS_MOVE: "INACCURACY",
    chess.pgn.NAG_MISTAKE: "MISTAKE",
    chess.pgn.NAG_BLUNDER: "BLUNDER",
}


@dataclass(frozen=True)
class Reference:
    """Lichess's eval after a ply and its judgment of the move, if any."""

    eval: Eval
    label: Classification | None


def references(pgn: str) -> list[Reference | None]:
    """One entry per ply; None where Lichess has no eval (the mating move)."""
    game = chess.pgn.read_game(io.StringIO(pgn))
    assert game is not None
    refs: list[Reference | None] = []
    for node in game.mainline():
        score = node.eval()
        if score is None:
            refs.append(None)
            continue
        white = score.white()
        label = next((NAG_LABELS[n] for n in node.nags if n in NAG_LABELS), None)
        refs.append(Reference(Eval(cp=white.score(), mate=white.mate()), label))
    return refs


@dataclass(frozen=True)
class Metric:
    id: str
    name: str
    value: str
    criterion: str
    passed: bool


@dataclass(frozen=True)
class Compared:
    """One ply that Lichess judged: our analysis next to Lichess's."""

    game: str
    ours: MoveAnalysis
    ref: Reference
    ours_before: Eval
    ref_before: Eval | None


def label_metrics(compared: Sequence[Compared]) -> list[Metric]:
    """E3-E5 over the plies Lichess evaluated."""
    ours_serious = {i for i, c in enumerate(compared) if c.ours.classification in SERIOUS}
    ref_serious = {i for i, c in enumerate(compared) if c.ref.label in SERIOUS}
    hits = len(ours_serious & ref_serious)
    recall = hits / len(ref_serious) if ref_serious else 1.0
    precision = hits / len(ours_serious) if ours_serious else 1.0

    blunders = [c for c in compared if c.ref.label == "BLUNDER"]
    agreed = sum(c.ours.classification == "BLUNDER" for c in blunders) / len(blunders) if blunders else 1.0
    missed = sum(c.ours.classification in ("BEST", "GOOD") for c in blunders)

    diffs = [
        abs(c.ours.eval.cp - c.ref.eval.cp)
        for c in compared
        if c.ours.eval.cp is not None
        and c.ref.eval.cp is not None
        and abs(c.ours.eval.cp) <= 500
        and abs(c.ref.eval.cp) <= 500
    ]
    median = statistics.median(diffs) if diffs else 0.0
    return [
        Metric(
            "E3",
            "Serious errors (MISTAKE or BLUNDER) vs Lichess",
            f"recall {recall:.2f} ({hits}/{len(ref_serious)}), precision {precision:.2f} "
            f"({hits}/{len(ours_serious)})",
            "recall >= 0.80 (was 0.85, lowered with sign-off 2026-10-27) and precision >= 0.80",
            recall >= 0.80 and precision >= 0.80,
        ),
        Metric(
            "E4",
            "Blunder agreement",
            f"{agreed:.0%} of {len(blunders)} Lichess blunders are ours; {missed} labeled BEST or GOOD",
            ">= 75%, and at most 2 labeled BEST or GOOD (was 1, raised with sign-off 2026-10-27)",
            agreed >= 0.75 and missed <= 2,
        ),
        Metric(
            "E5",
            "Eval agreement",
            f"median |cp difference| {median:.0f} over {len(diffs)} positions",
            "<= 50, where both are non-mate and |cp| <= 500",
            median <= 50,
        ),
    ]


def p95(values: Sequence[float]) -> float:
    return statistics.quantiles(values, n=20, method="inclusive")[-1] if len(values) > 1 else values[0]


@dataclass(frozen=True)
class Job:
    game: str
    result: AnalysisResult
    seconds: float


async def run_all(worker: Worker, games: dict[str, str]) -> list[Job]:
    jobs = []
    for name, pgn in games.items():
        request = AnalysisRequest(
            schema_version=SCHEMA_VERSION,
            job_id=uuid4(),  # a new id makes the engine start a new game, so its hash is fresh
            match_id=uuid4(),
            pgn=pgn,
            deadline=datetime.now(UTC) + timedelta(hours=1),
        )
        start = time.perf_counter()
        # The worker's job path without the broker: budget, engine retry and error mapping included
        result = await worker._analyze(request)
        jobs.append(Job(name, result, time.perf_counter() - start))
        print(f"{name}: {result.status} in {jobs[-1].seconds:.1f}s", file=sys.stderr)
    return jobs


def moves_json(job: Job) -> list[str]:
    return [m.model_dump_json() for m in job.result.moves or []]


def compare(jobs: Sequence[Job], games: dict[str, str]) -> list[Compared]:
    compared = []
    for job in jobs:
        if job.result.moves is None or job.result.initial_eval is None:
            continue
        refs = references(games[job.game])
        for i, (ours, ref) in enumerate(zip(job.result.moves, refs, strict=True)):
            if ref is None:
                continue
            before = job.result.moves[i - 1].eval if i else job.result.initial_eval
            ref_before = refs[i - 1] if i else None
            compared.append(Compared(job.game, ours, ref, before, ref_before.eval if ref_before else None))
    return compared


def evaluate(
    first: Sequence[Job], second: Sequence[Job], games: dict[str, str]
) -> tuple[list[Metric], list[Compared]]:
    failed = [f"{j.game} ({j.result.error.code})" for j in first if j.result.error is not None]
    differing = [a.game for a, b in zip(first, second, strict=True) if moves_json(a) != moves_json(b)]
    latency = p95([j.seconds for j in first])
    reference_games = [j for j in first if j.game.startswith("lichess-")]
    compared = compare(reference_games, games)
    metrics = [
        Metric(
            "E1",
            "Completion",
            f"{len(first) - len(failed)}/{len(first)} COMPLETED"
            + (f"; failed: {', '.join(failed)}" if failed else ""),
            "every game COMPLETED, 0 worker errors",
            not failed,
        ),
        Metric(
            "E2",
            "Determinism",
            "identical moves in both runs" if not differing else f"moves differ: {', '.join(differing)}",
            "a second run gives byte-identical moves for every game",
            not differing,
        ),
        *label_metrics(compared),
        Metric(
            "E6", "Latency", f"p95 job time {latency:.1f}s", "p95 <= 60 s (target <= 30 s)", latency <= 60
        ),
    ]
    return metrics, compared


def mismatches(compared: Sequence[Compared]) -> list[Compared]:
    """Plies where we and Lichess disagree on a serious error or a blunder."""
    return [
        c
        for c in compared
        if (c.ours.classification in SERIOUS) != (c.ref.label in SERIOUS)
        or (c.ref.label == "BLUNDER") != (c.ours.classification == "BLUNDER")
    ]


def _move(c: Compared) -> str:
    number = (c.ours.ply + 1) // 2
    return f"{number}{'.' if c.ours.ply % 2 else '...'}{c.ours.san}"


def report_md(
    engine: str, depth: int, metrics: Sequence[Metric], first: Sequence[Job], compared: Sequence[Compared]
) -> str:
    lines = [
        "# Engine eval report",
        "",
        f"{engine}, depth {depth}, no time cap, {len(first)} games. Regenerate with `python -m eval.run`.",
        "",
        "| # | Metric | Result | Pass | Status |",
        "| --- | --- | --- | --- | --- |",
        *(
            f"| {m.id} | {m.name} | {m.value} | {m.criterion} | {'PASS' if m.passed else 'FAIL'} |"
            for m in metrics
        ),
        "",
        "## Games",
        "",
        "| Game | Plies | Seconds | Our serious errors | Lichess serious errors |",
        "| --- | --- | --- | --- | --- |",
    ]
    for job in first:
        ours = sum(m.classification in SERIOUS for m in job.result.moves or [])
        theirs = sum(c.ref.label in SERIOUS for c in compared if c.game == job.game)
        reference = str(theirs) if job.game.startswith("lichess-") else "-"
        lines.append(
            f"| {job.game} | {len(job.result.moves or [])} | {job.seconds:.1f} | {ours} | {reference} |"
        )
    lines += [
        "",
        "## Disagreements on serious errors and blunders",
        "",
        "Evals are from White's point of view, before and after the move.",
        "",
        "| Game | Move | Ours | Lichess | Our eval | Lichess eval |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for c in mismatches(compared):
        ref_before = fmt_eval(c.ref_before) if c.ref_before else "-"
        lines.append(
            f"| {c.game} | {_move(c)} | {c.ours.classification} | {c.ref.label or '-'} "
            f"| {fmt_eval(c.ours_before)} → {fmt_eval(c.ours.eval)} | {ref_before} → {fmt_eval(c.ref.eval)} |"
        )
    return "\n".join(lines) + "\n"


async def main() -> int:
    games = {path.stem: path.read_text() for path in sorted(GAMES.glob("*.pgn"))}
    settings = Settings(engine_position_timeout=0)
    # Both runs at once, each on its own engine process, so E2 also covers determinism across processes
    workers = (Worker(settings), Worker(settings))
    try:
        first, second = await asyncio.gather(*(run_all(w, games) for w in workers))
    finally:
        for worker in workers:
            if worker._engine_transport is not None:
                worker._engine_transport.close()

    metrics, compared = evaluate(first, second, games)
    engine = next((j.result.engine.name for j in first if j.result.engine), "unknown engine")
    report = {
        "engine": engine,
        "depth": settings.engine_depth,
        "metrics": [asdict(m) for m in metrics],
        "games": [
            {
                "game": j.game,
                "status": j.result.status,
                "plies": len(j.result.moves or []),
                "seconds": round(j.seconds, 2),
            }
            for j in first
        ],
    }
    (EVAL_DIR / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    markdown = report_md(engine, settings.engine_depth, metrics, first, compared)
    (EVAL_DIR / "report.md").write_text(markdown)
    print(markdown)
    return 0 if all(m.passed for m in metrics) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
