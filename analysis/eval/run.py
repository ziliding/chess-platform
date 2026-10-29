"""Engine eval (spec §12.2): analyze `eval/games` through the worker's job path and score E1-E6.

The `lichess-*.pgn` games come from the Lichess database (CC0), exported with Lichess's own computer
analysis: a `[%eval]` after every move and a judgment glyph (?!, ?, ??) on every inaccuracy, mistake and
blunder. Those are the reference labels. The `platform-*.pgn` games come from the Java formatter; they only
test format compatibility and completion.

From `analysis/`: `uv run python -m eval.run`. It writes `eval/report.json` and `eval/report.md` and exits 1
if any metric fails. The run is depth-only (`ENGINE_POSITION_TIMEOUT=0`), so it is deterministic.

With `--llm`, the first run also asks the LLM that `LLM_BASE_URL`, `LLM_MODEL` and `LLM_API_KEY` configure,
and the report adds L1-L6 (spec §12.3) and five games for the L7 spot check. The second run stays engine-only:
E2 compares the moves without the explanations, and L6 checks the template summaries.
"""

import asyncio
import io
import json
import logging
import random
import re
import statistics
import sys
import time
from collections.abc import Sequence
from dataclasses import asdict, dataclass, replace
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
from analysis.engine import fmt_eval, numbered
from analysis.llm import Attempt
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
    seconds: float  # the engine step; the LLM step's time is in `llm`
    llm: Attempt | None = None  # None without an LLM, or when the breaker skipped it


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
        llm = worker.explainer.last if worker.explainer else None
        seconds = time.perf_counter() - start - (llm.seconds if llm else 0)
        jobs.append(Job(name, result, seconds, llm))
        print(f"{name}: {result.status} in {seconds:.1f}s", file=sys.stderr)
    return jobs


def moves_json(job: Job) -> list[str]:
    # Without the LLM's explanations, which no two runs share
    return [m.model_dump_json(exclude={"explanation"}) for m in job.result.moves or []]


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
    first: Sequence[Job], second: Sequence[Job], games: dict[str, str], llm: bool = False
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
        *(llm_metrics(first, second) if llm else []),
    ]
    return metrics, compared


# A SAN move as LLM text cites it, with an optional move number: "12.Nf3", "12...exd5", "Nf6", "O-O"
SAN = r"O-O-O|O-O|[KQRBN][a-h]?[1-8]?x?[a-h][1-8](?:=[QRBN])?|[a-h](?:x[a-h])?[1-8](?:=[QRBN])?"
# A move number is not part of a decimal ("-2.6. Bxd5" cites Bxd5, not 6.Bxd5), and White's has no space after
# its dot ("mate in 11. Kc5" cites Kc5, not 11.Kc5)
CITATION = re.compile(rf"(?:(?<![\w.+-])(\d+)(\.\.\.\s*|…\s*|\.(?!\s)))?(?<![\w-])({SAN})[+#]?(?![\w-])")
BARE_SQUARE = re.compile(r"[a-h][1-8]")


@dataclass(frozen=True)
class Citation:
    ply: int | None  # None when the text gives no move number
    san: str  # without + or #


def citations(text: str) -> list[Citation]:
    """The moves `text` cites. A bare "f7" reads as a square, not a pawn move, unless it has a move number."""
    found = []
    for number, dots, san in CITATION.findall(text):
        if not number and BARE_SQUARE.fullmatch(san):
            continue
        ply = int(number) * 2 - (1 if dots == "." else 0) if number else None  # a lone "." is White's
        found.append(Citation(ply, san))
    return found


def grounded(citation: Citation, moves: Sequence[MoveAnalysis]) -> bool:
    """L2: the move played at that ply or the engine's best move there; at any ply when there is no number."""
    at = moves if citation.ply is None else [m for m in moves if m.ply == citation.ply]
    return any(citation.san in (m.san.rstrip("+#"), m.best_san.rstrip("+#")) for m in at)


def ungrounded(text: str, moves: Sequence[MoveAnalysis]) -> list[Citation]:
    return [c for c in citations(text) if not grounded(c, moves)]


def llm_texts(result: AnalysisResult) -> list[str]:
    summary = [result.summary.text] if result.summary else []
    return summary + [m.explanation for m in result.moves or [] if m.explanation]


def cites_turning_point(result: AnalysisResult) -> bool:
    """L3: the summary cites the move with the largest win-chance loss."""
    moves = result.moves or []
    worst = max(moves, key=lambda m: m.win_chance_loss)
    return result.summary is not None and any(
        c.san == worst.san.rstrip("+#") and c.ply in (None, worst.ply) for c in citations(result.summary.text)
    )


def llm_metrics(first: Sequence[Job], second: Sequence[Job]) -> list[Metric]:
    """L1-L6 over the first run, which used the LLM; L6 over the second run's template summaries."""
    completed = [j for j in first if j.result.status == "COMPLETED"]
    valid_first = sum(1 for j in completed if j.llm is not None and j.llm.first_call_valid)
    explained = [j.result for j in completed if j.result.summary and j.result.summary.source == "LLM"]

    cited = wrong = clean = 0
    for result in explained:
        texts = llm_texts(result)
        bad = sum(len(ungrounded(t, result.moves or [])) for t in texts)
        cited += sum(len(citations(t)) for t in texts)
        wrong += bad
        clean += not bad
    grounded_share = (cited - wrong) / cited if cited else 1.0
    clean_share = clean / len(explained) if explained else 0.0

    with_blunder = [r for r in explained if any(m.classification == "BLUNDER" for m in r.moves or [])]
    turning = sum(cites_turning_point(r) for r in with_blunder)
    long_summaries = sum(len(r.summary.text.split()) > 80 for r in explained if r.summary)
    long_explanations = sum(len((m.explanation or "").split()) > 40 for r in explained for m in r.moves or [])
    attempts = [j.llm for j in first if j.llm is not None]
    latency = p95([a.seconds for a in attempts]) if attempts else 0.0
    tokens = statistics.mean(a.tokens for a in attempts) if attempts else 0.0
    templates = [j.result for j in second if j.result.summary]
    template_misses = [r.job_id for r in templates if r.summary and ungrounded(r.summary.text, r.moves or [])]

    def share(n: int, of: int) -> str:
        return f"{n / of:.0%} ({n}/{of})" if of else "n/a (0)"

    return [
        Metric(
            "L1",
            "Schema validity",
            f"{share(valid_first, len(completed))} valid on the first call; "
            f"{len(completed)}/{len(first)} COMPLETED, {len(completed) - len(explained)} on the template",
            ">= 95% valid on the first call; 100% of jobs COMPLETED",
            bool(completed) and valid_first / len(completed) >= 0.95 and len(completed) == len(first),
        ),
        Metric(
            "L2",
            "Grounding",
            f"{share(cited - wrong, cited)} of citations grounded; "
            f"{share(clean, len(explained))} of games with none ungrounded",
            ">= 98% of citations, and >= 90% of games with no ungrounded citation",
            grounded_share >= 0.98 and clean_share >= 0.90,
        ),
        Metric(
            "L3",
            "Turning point",
            f"{share(turning, len(with_blunder))} of games with a blunder",
            ">= 80% of the games that contain a BLUNDER",
            not with_blunder or turning / len(with_blunder) >= 0.80,
        ),
        Metric(
            "L4",
            "Length",
            f"{long_summaries} summaries over 80 words, {long_explanations} explanations over 40",
            "every summary <= 80 words, every explanation <= 40",
            bool(explained) and not long_summaries and not long_explanations,
        ),
        Metric(
            "L5",
            "Latency and cost",
            f"p95 LLM step {latency:.1f}s; {tokens:.0f} tokens per game",
            "p95 <= 15 s; tokens reported",
            bool(attempts) and latency <= 15,
        ),
        Metric(
            "L6",
            "Checker self-test",
            f"{share(len(templates) - len(template_misses), len(templates))} of template summaries grounded",
            "100%",
            bool(templates) and not template_misses,
        ),
    ]


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


def llm_md(model: str, first: Sequence[Job]) -> str:
    """The L7 spot check (five games, the same five every run) and every ungrounded citation, for L2."""
    explained = [j for j in first if j.result.summary and j.result.summary.source == "LLM"]
    lines = [
        "",
        f"## LLM output ({model})",
        "",
        "### Ungrounded citations (L2)",
        "",
    ]
    for job in explained:
        for text in llm_texts(job.result):
            for c in ungrounded(text, job.result.moves or []):
                lines.append(f"- {job.game}: `{c.san}` at ply {c.ply or 'any'} in “{text}”")
    lines += [
        "",
        "### Spot check (L7)",
        "",
        "Read each game and judge whether it is accurate and useful. L7 passes at 4 of 5.",
    ]
    for job in sorted(random.Random(7).sample(explained, min(5, len(explained))), key=lambda j: j.game):
        assert job.result.summary is not None
        lines += ["", f"#### {job.game}", "", job.result.summary.text, ""]
        lines += [
            f"- {numbered(m.ply, m.san)} ({m.classification}, best {m.best_san}): {m.explanation}"
            for m in job.result.moves or []
            if m.explanation
        ]
    return "\n".join(lines) + "\n"


def game_row(job: Job) -> dict[str, object]:
    row: dict[str, object] = {
        "game": job.game,
        "status": job.result.status,
        "plies": len(job.result.moves or []),
        "seconds": round(job.seconds, 2),
    }
    if job.llm is not None:
        row |= {
            "summarySource": job.result.summary.source if job.result.summary else None,
            "llmSeconds": round(job.llm.seconds, 2),
            "llmTokens": job.llm.tokens,
            # So the checker can be rerun on the same output
            "llmTexts": llm_texts(job.result),
        }
    return row


async def main(llm: bool) -> int:
    # Shows why the LLM step needed a repair or fell back
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    games = {path.stem: path.read_text() for path in sorted(GAMES.glob("*.pgn"))}
    with_llm = Settings(engine_position_timeout=0)
    # Off unless asked for, even when LLM_* is set in the shell: the engine eval must stay deterministic
    engine_only = replace(with_llm, llm_base_url="", llm_model="")
    # Both runs at once, each on its own engine process, so E2 also covers determinism across processes
    workers = (Worker(with_llm if llm else engine_only), Worker(engine_only))
    if llm and workers[0].explainer is None:
        print("--llm needs LLM_BASE_URL and LLM_MODEL", file=sys.stderr)
        return 2
    try:
        first, second = await asyncio.gather(*(run_all(w, games) for w in workers))
    finally:
        for worker in workers:
            if worker._engine_transport is not None:
                worker._engine_transport.close()

    metrics, compared = evaluate(first, second, games, llm)
    engine = next((j.result.engine.name for j in first if j.result.engine), "unknown engine")
    report = {
        "engine": engine,
        "depth": with_llm.engine_depth,
        "llmModel": with_llm.llm_model if llm else None,
        "metrics": [asdict(m) for m in metrics],
        "games": [game_row(j) for j in first],
    }
    (EVAL_DIR / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    markdown = report_md(engine, with_llm.engine_depth, metrics, first, compared)
    if llm:
        markdown += llm_md(with_llm.llm_model, first)
    (EVAL_DIR / "report.md").write_text(markdown)
    print(markdown)
    return 0 if all(m.passed for m in metrics) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main(llm="--llm" in sys.argv[1:])))
