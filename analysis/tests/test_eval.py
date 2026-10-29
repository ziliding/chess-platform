"""The eval's own math: a bug here would print a false PASS."""

from analysis.contracts import Classification, Eval, MoveAnalysis
from eval.run import Compared, Reference, label_metrics, mismatches, references


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
