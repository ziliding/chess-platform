import chess
import chess.engine
import pytest

from analysis.contracts import Eval, MoveAnalysis
from analysis.engine import (
    AnalysisError,
    analyze,
    classify,
    fmt_eval,
    parse_game,
    score_from_engine,
    score_terminal,
    win_chances,
)
from tests.conftest import TEST_DEPTH

SCHOLARS_MATE = "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0"
# Sam Loyd's ten-move stalemate
STALEMATE = (
    "1. e3 a5 2. Qh5 Ra6 3. Qxa5 h5 4. h4 Rah6 5. Qxc7 f6 6. Qxd7+ Kf7 7. Qxb7 Qd3 "
    "8. Qxb8 Qh7 9. Qxc8 Kg6 10. Qe6 1/2-1/2"
)
PROMOTION = "1. a4 b5 2. axb5 a6 3. bxa6 Nc6 4. a7 Nb8 5. axb8=Q 1-0"


def test_win_chances_is_symmetric_and_bounded():
    assert win_chances(0) == 0
    assert win_chances(300) == pytest.approx(-win_chances(-300))
    assert 0 < win_chances(100) < win_chances(500) < 1


@pytest.mark.parametrize(
    ("loss", "expected"),
    [
        (0.0, "GOOD"),
        (0.09, "GOOD"),
        (0.1, "INACCURACY"),
        (0.2, "MISTAKE"),
        (0.3, "BLUNDER"),
        (1.5, "BLUNDER"),
    ],
)
def test_classify_thresholds(loss, expected):
    assert classify(chess.Move.from_uci("e2e4"), chess.Move.from_uci("d2d4"), loss) == expected


def test_best_move_is_best_whatever_the_loss():
    move = chess.Move.from_uci("e2e4")
    assert classify(move, move, 0.5) == "BEST"


def test_terminal_positions_are_scored_without_the_engine():
    mated_black = chess.Board("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
    assert mated_black.is_checkmate()
    scored = score_terminal(mated_black)
    assert scored is not None
    assert scored.eval == Eval(mate=0)
    assert scored.chances == -1.0  # White is mated
    assert score_terminal(chess.Board()) is None


def test_engine_mate_scores_are_from_whites_view():
    black_mates = chess.engine.PovScore(chess.engine.Mate(2), chess.BLACK)
    scored = score_from_engine(black_mates)
    assert scored.eval == Eval(mate=-2)
    assert scored.chances == -1.0


def test_fmt_eval():
    assert fmt_eval(Eval(cp=30)) == "+0.3"
    assert fmt_eval(Eval(cp=-120)) == "-1.2"
    assert fmt_eval(Eval(mate=-3)) == "mate in 3"
    assert fmt_eval(Eval(mate=0)) == "checkmate"


@pytest.mark.parametrize(
    ("pgn", "code"),
    [
        ("", "INVALID_PGN"),
        ('[Result "*"]\n\n*', "INVALID_PGN"),
        ("1. e4 e5 2. Nf9", "INVALID_PGN"),
        ("1. e4 e5 2. Ke3", "INVALID_PGN"),
    ],
)
def test_invalid_pgn(pgn, code):
    with pytest.raises(AnalysisError) as e:
        parse_game(pgn)
    assert e.value.code == code


def test_too_long():
    pgn = " ".join("Nf3 Nf6 Ng1 Ng8".split() * 101)
    with pytest.raises(AnalysisError) as e:
        parse_game(pgn)
    assert e.value.code == "TOO_LONG"


async def run(engine, pgn: str):
    return await analyze(engine, pgn, TEST_DEPTH, 2, game_token=object())


def move(result, ply: int) -> MoveAnalysis:
    return result.moves[ply - 1]


async def test_scholars_mate(engine):
    result = await run(engine, SCHOLARS_MATE)

    assert [m.san for m in result.moves] == ["e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#"]
    blunder = move(result, 6)
    assert blunder.classification == "BLUNDER"
    assert blunder.best_san != "Nf6"
    assert blunder.eval.mate == 1  # White mates in one
    assert move(result, 7).eval == Eval(mate=0)
    assert move(result, 7).classification == "BEST"
    assert "White won by checkmate." in result.summary.text
    assert "3...Nf6??" in result.summary.text
    assert result.summary.source == "TEMPLATE"
    assert result.engine.name.startswith("Stockfish")


async def test_evals_are_whites_view_and_losses_are_the_movers(engine):
    result = await run(engine, SCHOLARS_MATE)

    # White's winning move cannot lose chances, and neither can the mate
    assert move(result, 7).win_chance_loss == 0
    assert all(m.win_chance_loss >= 0 for m in result.moves)
    assert all((m.eval.cp is None) != (m.eval.mate is None) for m in result.moves)


async def test_stalemate_is_scored_as_a_draw(engine):
    result = await run(engine, STALEMATE)

    assert len(result.moves) == 19
    assert move(result, 19).eval == Eval(cp=0)
    assert result.summary.text.endswith("The game was drawn.")


async def test_promotion(engine):
    result = await run(engine, PROMOTION)

    assert move(result, 9).san == "axb8=Q"
    assert move(result, 9).uci == "a7b8q"


async def test_analysis_is_deterministic_without_a_time_cap(engine):
    first = await analyze(engine, SCHOLARS_MATE, TEST_DEPTH, 0, game_token=1)
    second = await analyze(engine, SCHOLARS_MATE, TEST_DEPTH, 0, game_token=2)

    assert first.moves == second.moves
