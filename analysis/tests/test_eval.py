"""The eval's own math: a bug here would print a false PASS."""

from analysis.contracts import AnalysisResult, Classification, Eval, MoveAnalysis
from analysis.llm import Attempt
from eval.run import (
    Citation,
    Compared,
    Job,
    Reference,
    citations,
    label_metrics,
    llm_metrics,
    mismatches,
    references,
    ungrounded,
)
from tests.conftest import FIXTURES


def test_references_read_lichess_evals_and_judgment_glyphs():
    pgn = (
        "1. e4 { [%eval 0.18] } 1... e5 { [%eval 0.22] } 2. f4?! { [%eval -0.34] } "
        "2... Qh4+?? { [%eval #2] } 3. g3 1-0"
    )

    refs = references(pgn)

    assert refs[:4] == [
        Reference(Eval(cp=18), None),
        Reference(Eval(cp=22), None),
        Reference(Eval(cp=-34), "INACCURACY"),
        Reference(Eval(mate=2), "BLUNDER"),
    ]
    assert refs[4] is None  # no eval, so not compared


def ply(ours: Classification, ref: Classification | None, cp: int = 0, ref_cp: int = 0) -> Compared:
    move = MoveAnalysis(
        ply=1,
        san="e4",
        uci="e2e4",
        eval=Eval(cp=cp),
        best_san="d4",
        best_uci="d2d4",
        win_chance_loss=0.0,
        classification=ours,
    )
    return Compared("g", move, Reference(Eval(cp=ref_cp), ref), Eval(cp=0), None)


def test_label_metrics():
    compared = [
        ply("BLUNDER", "BLUNDER", cp=100, ref_cp=140),  # hit
        ply("MISTAKE", "BLUNDER", cp=0, ref_cp=10),  # hit for E3, a blunder we call a mistake for E4
        ply("GOOD", "MISTAKE", cp=-50, ref_cp=-30),  # missed
        ply("MISTAKE", "INACCURACY", cp=600, ref_cp=0),  # false alarm; |cp| > 500 leaves it out of E5
        ply("BEST", None, cp=20, ref_cp=20),
    ]

    e3, e4, e5 = label_metrics(compared)

    assert e3.value == "recall 0.67 (2/3), precision 0.67 (2/3)"
    assert not e3.passed
    assert e4.value == "50% of 2 Lichess blunders are ours; 0 labeled BEST or GOOD"
    assert not e4.passed
    assert e5.value == "median |cp difference| 15 over 4 positions"  # of 0, 10, 20 and 40
    assert e5.passed
    assert [c.ref.label for c in mismatches(compared)] == ["BLUNDER", "MISTAKE", "INACCURACY"]


def test_label_metrics_pass_on_full_agreement():
    compared = [ply("BLUNDER", "BLUNDER"), ply("MISTAKE", "MISTAKE"), ply("GOOD", "INACCURACY")]

    assert all(m.passed for m in label_metrics(compared))
    assert mismatches(compared) == []


TEMPLATE = AnalysisResult.model_validate_json((FIXTURES / "result.completed.json").read_text())
LLM = AnalysisResult.model_validate_json((FIXTURES / "result.completed.llm.json").read_text())


def test_citations_read_numbers_sides_and_squares():
    text = (
        "After 3.Qh5 and 3...Nf6?? White mates with Qxf7#; f7 was weak, 12…exd5 and O-O-O too, but 4.f3 not."
    )

    assert citations(text) == [
        Citation(5, "Qh5"),
        Citation(6, "Nf6"),
        Citation(None, "Qxf7"),
        # the bare f7 is a square
        Citation(24, "exd5"),
        Citation(None, "O-O-O"),
        Citation(7, "f3"),
    ]


def test_an_eval_or_a_count_before_a_move_is_not_its_number():
    # Sentences from the first hosted run, which the checker read as 6.Bxd5, 0.Rd4, 2.Bxb4 and 11.Kc5
    text = (
        "The position swings to -2.6. Bxd5 was better. It went to 0.0. Rd4 keeps the edge. "
        "From -0.3 to +1.2. Bxb4 takes the pawn. It allowed a mate in 11. Kc5 avoids it, and ...Qd5 follows."
    )

    assert citations(text) == [
        Citation(None, "Bxd5"),
        Citation(None, "Rd4"),
        Citation(None, "Bxb4"),
        Citation(None, "Kc5"),
        Citation(None, "Qd5"),
    ]


def test_grounding_accepts_the_played_and_the_best_move_at_that_ply():
    moves = TEMPLATE.moves or []

    assert ungrounded("3...Nf6?? lost; 3...g6 was best, and Qxf7# followed.", moves) == []
    assert ungrounded("3.Nf6 and 4...Qxf7 and 3...Nc6 and Kh1", moves) == [
        Citation(5, "Nf6"),  # Black's move, cited as White's
        Citation(8, "Qxf7"),  # White's move, cited as Black's
        Citation(6, "Nc6"),  # played, but at ply 4
        Citation(None, "Kh1"),  # never played or best
    ]


def test_template_summaries_are_grounded():
    """L6 on a real template summary: the checker must not flag the baseline."""
    assert TEMPLATE.summary is not None and TEMPLATE.moves is not None
    assert citations(TEMPLATE.summary.text) == [Citation(6, "Nf6"), Citation(6, "g6")]
    assert ungrounded(TEMPLATE.summary.text, TEMPLATE.moves) == []


def job(result: AnalysisResult, attempt: Attempt | None = None) -> Job:
    return Job("g", result, 1.0, attempt)


def test_llm_metrics_pass_on_good_output():
    first = [job(LLM, Attempt(first_call_valid=True, seconds=4.0, tokens=900))] * 3

    metrics = llm_metrics(first, [job(TEMPLATE)] * 3)

    assert [m.id for m in metrics] == ["L1", "L2", "L3", "L4", "L5", "L6"]
    assert all(m.passed for m in metrics), metrics
    assert metrics[4].value == "p95 LLM step 4.0s; 900 tokens per game"


def test_llm_metrics_catch_fallbacks_ungrounded_moves_and_long_text():
    assert LLM.summary is not None
    wordy = LLM.model_copy(
        update={
            "summary": LLM.summary.model_copy(update={"text": "Black castled with 7...O-O. " + "x " * 80})
        }
    )
    first = [job(TEMPLATE, Attempt(seconds=30.0)), job(wordy, Attempt(first_call_valid=True, seconds=3.0))]

    l1, l2, l3, l4, l5, l6 = llm_metrics(first, [job(TEMPLATE)])

    assert not l1.passed and "1 on the template" in l1.value
    assert not l2.passed and l2.value.startswith("80% (4/5) of citations grounded")
    assert not l3.passed  # the turning point, 3...Nf6, is not in the summary
    assert not l4.passed and l4.value.startswith("1 summaries over 80 words")
    assert not l5.passed
    assert l6.passed
