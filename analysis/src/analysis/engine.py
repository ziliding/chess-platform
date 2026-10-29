"""Stockfish analysis of a game: per-ply evals, move classification and a deterministic template summary."""

import asyncio
import io
import math
import re
from collections.abc import Sequence
from dataclasses import dataclass

import chess
import chess.engine
import chess.pgn

from analysis.contracts import (
    Classification,
    EngineInfo,
    ErrorCode,
    Eval,
    MoveAnalysis,
    Summary,
)

MAX_PLIES = 400

# lila's thresholds on winning chances in [-1, 1] (modules/analyse Advice.scala)
BLUNDER = 0.3
MISTAKE = 0.2
INACCURACY = 0.1


class AnalysisError(Exception):
    def __init__(self, code: ErrorCode, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def win_chances(cp: int) -> float:
    """White's winning chances in [-1, 1] for a centipawn score (lila's logistic)."""
    return 2 / (1 + math.exp(-0.00368208 * cp)) - 1


@dataclass(frozen=True)
class Scored:
    """A position's score from White's point of view."""

    eval: Eval
    chances: float  # win_chances for White, mate counted as +-1


def score_terminal(board: chess.Board) -> Scored | None:
    """Score a finished position without the engine; None if the game goes on."""
    if board.is_checkmate():
        # The side to move is mated
        return Scored(Eval(mate=0), 1.0 if board.turn == chess.BLACK else -1.0)
    if board.is_stalemate() or board.is_insufficient_material():
        return Scored(Eval(cp=0), 0.0)
    return None


def score_from_engine(score: chess.engine.PovScore) -> Scored:
    white = score.white()
    mate = white.mate()
    if mate is not None:
        return Scored(Eval(mate=mate), 1.0 if mate > 0 else -1.0)
    cp = white.score()
    assert cp is not None
    return Scored(Eval(cp=cp), win_chances(cp))


def classify(played: chess.Move, best: chess.Move, loss: float) -> Classification:
    if played == best:
        return "BEST"
    if loss >= BLUNDER:
        return "BLUNDER"
    if loss >= MISTAKE:
        return "MISTAKE"
    if loss >= INACCURACY:
        return "INACCURACY"
    return "GOOD"


RESULTS = {"1-0", "0-1", "1/2-1/2", "*"}


def _check_all_moves_read(pgn: str, read: int) -> None:
    """python-chess skips movetext it cannot tokenize (such as `Nf9`) instead of reporting an error."""
    movetext = "\n".join(line for line in pgn.splitlines() if not line.startswith("["))
    if any(c in movetext for c in "{;($"):
        return  # annotated PGN: comments and variations make a plain token count unreliable
    tokens = [t for t in re.sub(r"\d+\.+", " ", movetext).split() if t not in RESULTS]
    if len(tokens) != read:
        raise AnalysisError("INVALID_PGN", f"movetext has {len(tokens)} moves but {read} could be read")


def parse_game(pgn: str) -> tuple[chess.Board, list[chess.Move], str]:
    game = chess.pgn.read_game(io.StringIO(pgn))
    if game is None:
        raise AnalysisError("INVALID_PGN", "no game found")
    if game.errors:
        raise AnalysisError("INVALID_PGN", str(game.errors[0]))
    moves = list(game.mainline_moves())
    if not moves:
        raise AnalysisError("INVALID_PGN", "game has no moves")
    _check_all_moves_read(pgn, len(moves))
    if len(moves) > MAX_PLIES:
        raise AnalysisError("TOO_LONG", f"{len(moves)} plies, the limit is {MAX_PLIES}")
    return game.board(), moves, game.headers.get("Result", "*")


@dataclass(frozen=True)
class Analyzed:
    engine: EngineInfo
    initial_eval: Eval
    moves: list[MoveAnalysis]
    summary: Summary
    # Per ply, the engine's line from the position before it (SAN, numbered); for the LLM, not on the wire
    best_lines: list[str]


# Moves of the engine's line kept for the LLM (spec §10)
BEST_LINE_PLIES = 4

# How long past its time cap the engine may take to answer before it counts as hung
HANG_GRACE_SECONDS = 10


async def analyze(
    engine: chess.engine.UciProtocol, pgn: str, depth: int, position_timeout: float, game_token: object
) -> Analyzed:
    """Analyze every position of the game. `position_timeout` of 0 means depth only (deterministic).

    Raises TimeoutError if the engine hangs on a position. Without a time cap, the job budget catches hangs.
    """
    board, moves, result = parse_game(pgn)
    limit = chess.engine.Limit(depth=depth, time=position_timeout or None)
    hang_timeout = position_timeout + HANG_GRACE_SECONDS if position_timeout else None

    async def evaluate(position: chess.Board) -> tuple[Scored, list[chess.Move]]:
        terminal = score_terminal(position)
        if terminal is not None:
            return terminal, []
        async with asyncio.timeout(hang_timeout):
            info = await engine.analyse(position, limit, game=game_token)
        return score_from_engine(info["score"]), info.get("pv", [])

    current, line = await evaluate(board)
    initial = previous = current
    analyzed: list[MoveAnalysis] = []
    best_lines: list[str] = []
    for ply, move in enumerate(moves, start=1):
        before = board.copy()
        best_move = line[0] if line else move
        san = board.san(move)
        board.push(move)
        current, next_line = await evaluate(board)
        white_moved = ply % 2 == 1
        delta = current.chances - previous.chances
        # The mover's loss is White's drop, or White's gain when Black moved
        loss = max(0.0, -delta if white_moved else delta)
        analyzed.append(
            MoveAnalysis(
                ply=ply,
                san=san,
                uci=move.uci(),
                eval=current.eval,
                best_san=before.san(best_move),
                best_uci=best_move.uci(),
                win_chance_loss=round(loss, 4),
                classification=classify(move, best_move, loss),
            )
        )
        best_lines.append(before.variation_san(line[:BEST_LINE_PLIES]))
        previous, line = current, next_line
    name = engine.id.get("name", "Stockfish")
    return Analyzed(
        engine=EngineInfo(name=name, depth=depth),
        initial_eval=initial.eval,
        moves=analyzed,
        summary=Summary(text=summarize(initial.eval, analyzed, board, result), source="TEMPLATE"),
        best_lines=best_lines,
    )


def fmt_eval(ev: Eval) -> str:
    if ev.mate is not None:
        return "checkmate" if ev.mate == 0 else f"mate in {abs(ev.mate)}"
    assert ev.cp is not None
    return f"{ev.cp / 100:+.1f}"


def numbered(ply: int, san: str) -> str:
    """`3.Bc4` for White's third move, `3...Nf6` for Black's."""
    return f"{(ply + 1) // 2}{'.' if ply % 2 == 1 else '...'}{san}"


def _label(move: MoveAnalysis) -> str:
    mark = {"INACCURACY": "?!", "MISTAKE": "?", "BLUNDER": "??"}.get(move.classification, "")
    return numbered(move.ply, move.san) + mark


def _plural(n: int, singular: str, plural: str) -> str:
    return f"{n} {singular if n == 1 else plural}"


def _counts(side: str, moves: Sequence[MoveAnalysis]) -> str:
    def count(kind: Classification) -> int:
        return sum(1 for m in moves if m.classification == kind)

    return (
        f"{side}: {_plural(count('INACCURACY'), 'inaccuracy', 'inaccuracies')}, "
        f"{_plural(count('MISTAKE'), 'mistake', 'mistakes')}, "
        f"{_plural(count('BLUNDER'), 'blunder', 'blunders')}."
    )


def summarize(initial: Eval, moves: Sequence[MoveAnalysis], final_board: chess.Board, result: str) -> str:
    parts = [_counts("White", moves[0::2]), _counts("Black", moves[1::2])]
    errors = [m for m in moves if m.classification in ("INACCURACY", "MISTAKE", "BLUNDER")]
    if errors:
        worst = max(errors, key=lambda m: m.win_chance_loss)
        before = moves[worst.ply - 2].eval if worst.ply > 1 else initial
        parts.append(
            f"Turning point: {_label(worst)} ({_label_best(worst)} was best), "
            f"from {fmt_eval(before)} to {fmt_eval(worst.eval)}."
        )
    parts.append(_result_sentence(final_board, result))
    return " ".join(parts)


def _label_best(move: MoveAnalysis) -> str:
    return numbered(move.ply, move.best_san)


def _result_sentence(board: chess.Board, result: str) -> str:
    if board.is_checkmate():
        return f"{'White' if board.turn == chess.BLACK else 'Black'} won by checkmate."
    return {
        "1-0": "White won.",
        "0-1": "Black won.",
        "1/2-1/2": "The game was drawn.",
    }.get(result, "The game has no recorded result.")
