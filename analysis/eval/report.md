# Engine eval report

Stockfish 17.1, depth 16, no time cap, 28 games. Regenerate with `python -m eval.run`.

| # | Metric | Result | Pass | Status |
| --- | --- | --- | --- | --- |
| E1 | Completion | 28/28 COMPLETED | every game COMPLETED, 0 worker errors | PASS |
| E2 | Determinism | identical moves in both runs | a second run gives byte-identical moves for every game | PASS |
| E3 | Serious errors (MISTAKE or BLUNDER) vs Lichess | recall 0.84 (162/194), precision 0.86 (162/188) | recall >= 0.80 (was 0.85, lowered with sign-off 2026-10-27) and precision >= 0.80 | PASS |
| E4 | Blunder agreement | 78% of 125 Lichess blunders are ours; 2 labeled BEST or GOOD | >= 75%, and at most 2 labeled BEST or GOOD (was 1, raised with sign-off 2026-10-27) | PASS |
| E5 | Eval agreement | median |cp difference| 15 over 1025 positions | <= 50, where both are non-mate and |cp| <= 500 | PASS |
| E6 | Latency | p95 job time 26.8s | p95 <= 60 s (target <= 30 s) | PASS |
| L1 | Schema validity | 100% (28/28) valid on the first call; 28/28 COMPLETED, 0 on the template | >= 95% valid on the first call; 100% of jobs COMPLETED | PASS |
| L2 | Grounding | 100% (231/232) of citations grounded; 96% (27/28) of games with none ungrounded | >= 98% of citations, and >= 90% of games with no ungrounded citation | PASS |
| L3 | Turning point | 100% (25/25) of games with a blunder | >= 80% of the games that contain a BLUNDER | PASS |
| L4 | Length | 0 summaries over 80 words, 0 explanations over 40 | every summary <= 80 words, every explanation <= 40 | PASS |
| L5 | Latency and cost | p95 LLM step 10.9s; 3529 tokens per game | p95 <= 15 s; tokens reported | PASS |
| L6 | Checker self-test | 100% (28/28) of template summaries grounded | 100% | PASS |

## Games

| Game | Plies | Seconds | Our serious errors | Lichess serious errors |
| --- | --- | --- | --- | --- |
| lichess-0z3mP6cN | 60 | 9.8 | 6 | 6 |
| lichess-2Rt3Z9M5 | 64 | 11.8 | 3 | 4 |
| lichess-3m5zv1Vk | 39 | 5.7 | 2 | 3 |
| lichess-4EMSkHTu | 62 | 15.3 | 14 | 11 |
| lichess-5QsQ8Cp3 | 27 | 10.7 | 4 | 4 |
| lichess-8Pw6ookq | 46 | 11.4 | 6 | 6 |
| lichess-9izSqUo9 | 101 | 22.2 | 10 | 9 |
| lichess-AJOqh22R | 95 | 8.5 | 4 | 8 |
| lichess-BD5ftnrR | 125 | 11.9 | 8 | 5 |
| lichess-IEzKEvcT | 71 | 8.2 | 2 | 4 |
| lichess-Lm55Fufr | 173 | 17.6 | 6 | 5 |
| lichess-P3ZITVxp | 38 | 7.9 | 8 | 7 |
| lichess-Q8Kni4jL | 71 | 6.7 | 11 | 11 |
| lichess-QjIjWhJC | 95 | 21.6 | 5 | 4 |
| lichess-SZVqf5Ii | 59 | 17.9 | 17 | 17 |
| lichess-Wwylp8rn | 128 | 29.3 | 10 | 12 |
| lichess-ZjNGZsEJ | 57 | 17.5 | 7 | 7 |
| lichess-ahYXRgBp | 188 | 32.9 | 29 | 29 |
| lichess-fBjlu0qV | 91 | 15.3 | 3 | 3 |
| lichess-fDRgcj6S | 57 | 21.5 | 6 | 7 |
| lichess-g3bLb9fX | 24 | 7.7 | 0 | 0 |
| lichess-haNWbocW | 23 | 9.7 | 4 | 4 |
| lichess-nJJIXhCI | 81 | 16.2 | 5 | 9 |
| lichess-q7ymIJFQ | 43 | 15.6 | 13 | 13 |
| lichess-vyPxPj0e | 34 | 14.1 | 5 | 6 |
| platform-1 | 95 | 8.6 | 4 | - |
| platform-2 | 125 | 14.6 | 8 | - |
| platform-3 | 101 | 22.3 | 10 | - |

## Disagreements on serious errors and blunders

Evals are from White's point of view, before and after the move.

| Game | Move | Ours | Lichess | Our eval | Lichess eval |
| --- | --- | --- | --- | --- | --- |
| lichess-0z3mP6cN | 30.Rd8 | MISTAKE | BLUNDER | -5.0 → mate in 1 | -5.9 → mate in 1 |
| lichess-2Rt3Z9M5 | 27.Rfd1 | INACCURACY | BLUNDER | -6.7 → mate in 9 | -6.5 → mate in 8 |
| lichess-3m5zv1Vk | 10.b4 | INACCURACY | MISTAKE | +4.8 → +3.4 | +4.9 → +2.6 |
| lichess-4EMSkHTu | 6.f3 | MISTAKE | INACCURACY | +0.4 → -0.7 | +0.4 → -0.5 |
| lichess-4EMSkHTu | 6...Rc8 | MISTAKE | INACCURACY | -0.7 → +0.7 | -0.5 → +0.5 |
| lichess-4EMSkHTu | 9.d5 | BLUNDER | MISTAKE | +1.3 → -0.5 | +1.1 → -0.3 |
| lichess-4EMSkHTu | 19...h6 | MISTAKE | INACCURACY | -3.3 → -1.8 | -3.6 → -2.2 |
| lichess-4EMSkHTu | 21.Rfe1 | BLUNDER | MISTAKE | -2.1 → -4.9 | -3.0 → -5.3 |
| lichess-4EMSkHTu | 21...Re6 | BLUNDER | MISTAKE | -4.9 → -2.2 | -5.3 → -2.7 |
| lichess-4EMSkHTu | 22.Re2 | MISTAKE | BLUNDER | -2.2 → -4.4 | -2.7 → -5.5 |
| lichess-4EMSkHTu | 26.gxf3 | MISTAKE | BLUNDER | +5.6 → +3.0 | +7.0 → +3.4 |
| lichess-5QsQ8Cp3 | 9...d3 | MISTAKE | BLUNDER | +1.6 → +3.0 | +1.7 → +3.8 |
| lichess-5QsQ8Cp3 | 10.Bxd3 | INACCURACY | MISTAKE | +3.0 → +1.9 | +3.8 → +2.1 |
| lichess-5QsQ8Cp3 | 13.Bb5+ | MISTAKE | INACCURACY | +2.0 → +0.8 | +1.9 → +0.9 |
| lichess-8Pw6ookq | 2.Nf3 | BLUNDER | MISTAKE | +0.8 → -0.9 | +0.5 → -0.8 |
| lichess-8Pw6ookq | 11.g4 | MISTAKE | BLUNDER | -1.8 → -3.8 | -1.8 → -4.5 |
| lichess-9izSqUo9 | 20...Rxe5 | MISTAKE | - | +2.5 → +4.0 | +4.3 → +5.0 |
| lichess-9izSqUo9 | 44...Rxf3 | MISTAKE | BLUNDER | +1.3 → +2.9 | +1.2 → +4.1 |
| lichess-AJOqh22R | 5...d5 | MISTAKE | BLUNDER | -0.1 → +1.5 | +0.0 → +1.7 |
| lichess-AJOqh22R | 8...Nxd4 | MISTAKE | INACCURACY | +0.9 → +2.2 | +0.7 → +1.8 |
| lichess-AJOqh22R | 9...Be6 | INACCURACY | MISTAKE | +2.1 → +3.2 | +1.8 → +3.5 |
| lichess-AJOqh22R | 18.Re2 | GOOD | BLUNDER | +2.7 → +2.3 | +3.3 → +1.0 |
| lichess-AJOqh22R | 18...b5 | GOOD | BLUNDER | +2.3 → +2.9 | +1.0 → +4.3 |
| lichess-AJOqh22R | 19.Bb3 | GOOD | MISTAKE | +2.9 → +2.4 | +4.3 → +2.7 |
| lichess-AJOqh22R | 38...Kd6 | GOOD | MISTAKE | +7.3 → +7.0 | +7.9 → mate in 13 |
| lichess-BD5ftnrR | 14...c5 | MISTAKE | INACCURACY | +3.9 → +6.3 | +5.0 → +7.1 |
| lichess-BD5ftnrR | 23.Qe3 | MISTAKE | - | +7.2 → +4.3 | +7.0 → +5.8 |
| lichess-BD5ftnrR | 45...Bxd6 | INACCURACY | BLUNDER | +6.8 → +11.0 | +6.4 → mate in 11 |
| lichess-BD5ftnrR | 49.c7 | MISTAKE | - | mate in 10 → +4.8 | mate in 15 → mate in 15 |
| lichess-BD5ftnrR | 53...Kb6 | MISTAKE | - | +5.0 → mate in 11 | mate in 6 → mate in 6 |
| lichess-IEzKEvcT | 11.Nxe4 | INACCURACY | MISTAKE | +2.1 → +1.1 | +2.5 → +1.0 |
| lichess-IEzKEvcT | 33...Ra8 | GOOD | MISTAKE | +8.3 → +9.6 | +8.7 → mate in 9 |
| lichess-Lm55Fufr | 8.Nxd7 | MISTAKE | INACCURACY | +1.4 → +0.1 | +1.3 → +0.2 |
| lichess-Lm55Fufr | 9.Bd3 | MISTAKE | INACCURACY | +1.2 → -0.1 | +1.1 → +0.1 |
| lichess-Lm55Fufr | 65...Kg5 | BEST | MISTAKE | +7.1 → +8.1 | +9.1 → mate in 11 |
| lichess-P3ZITVxp | 9...Bb4 | MISTAKE | INACCURACY | +1.0 → +2.5 | +1.0 → +2.1 |
| lichess-P3ZITVxp | 15...Na6 | MISTAKE | BLUNDER | -1.6 → +0.0 | -2.2 → +0.0 |
| lichess-Q8Kni4jL | 7...O-O | MISTAKE | BLUNDER | +0.4 → +2.0 | +0.0 → +2.2 |
| lichess-QjIjWhJC | 15.Nxf6+ | MISTAKE | INACCURACY | +2.6 → +1.2 | +2.3 → +1.2 |
| lichess-QjIjWhJC | 18.Nf5 | INACCURACY | MISTAKE | +0.9 → +0.1 | +1.2 → +0.1 |
| lichess-QjIjWhJC | 45.Bb6 | MISTAKE | INACCURACY | +2.0 → +0.8 | +2.1 → +0.9 |
| lichess-SZVqf5Ii | 7.O-O | MISTAKE | INACCURACY | -0.7 → -1.9 | -0.8 → -1.5 |
| lichess-SZVqf5Ii | 7...Bb4 | MISTAKE | INACCURACY | -1.9 → -0.6 | -1.5 → -0.5 |
| lichess-SZVqf5Ii | 12...h6 | INACCURACY | MISTAKE | -2.0 → -1.0 | -2.1 → -0.9 |
| lichess-SZVqf5Ii | 16...g6 | GOOD | MISTAKE | +1.9 → +2.1 | +0.9 → +2.1 |
| lichess-SZVqf5Ii | 20.Bg3 | MISTAKE | BLUNDER | -0.8 → -2.0 | -0.7 → -3.3 |
| lichess-Wwylp8rn | 8.g4 | MISTAKE | INACCURACY | +0.2 → -1.1 | +0.0 → -0.9 |
| lichess-Wwylp8rn | 24.Qb4 | INACCURACY | MISTAKE | -1.2 → -1.9 | -1.1 → -2.5 |
| lichess-Wwylp8rn | 28...Qe7 | INACCURACY | MISTAKE | -2.5 → -1.8 | -3.0 → -1.3 |
| lichess-Wwylp8rn | 29.a3 | INACCURACY | MISTAKE | -1.8 → -2.5 | -1.3 → -2.9 |
| lichess-Wwylp8rn | 32.Rb4 | MISTAKE | BLUNDER | -2.5 → -4.7 | -2.8 → -7.4 |
| lichess-Wwylp8rn | 33...Qd5 | BLUNDER | MISTAKE | -5.8 → -2.6 | -6.6 → -3.8 |
| lichess-Wwylp8rn | 34.Rhh3 | BLUNDER | MISTAKE | -2.6 → -5.4 | -3.8 → -7.1 |
| lichess-Wwylp8rn | 37...Qc4 | BLUNDER | MISTAKE | -6.4 → -2.8 | -7.9 → -4.1 |
| lichess-ZjNGZsEJ | 16.Nc5 | MISTAKE | INACCURACY | +0.8 → -0.4 | +0.8 → +0.0 |
| lichess-ZjNGZsEJ | 21...Qxh2 | INACCURACY | MISTAKE | +2.9 → +4.3 | +3.9 → +6.2 |
| lichess-ahYXRgBp | 18.Nxc6 | MISTAKE | BLUNDER | +3.8 → +2.1 | +4.7 → +2.0 |
| lichess-ahYXRgBp | 22.Qf3 | MISTAKE | INACCURACY | +1.9 → +0.5 | +2.3 → +1.2 |
| lichess-ahYXRgBp | 22...Bf5 | MISTAKE | INACCURACY | +0.5 → +1.6 | +1.2 → +2.3 |
| lichess-ahYXRgBp | 24.Qh3 | BLUNDER | MISTAKE | +1.8 → +0.1 | +1.6 → +0.1 |
| lichess-ahYXRgBp | 31...Be6 | MISTAKE | BLUNDER | +1.0 → +2.8 | +1.0 → +7.4 |
| lichess-ahYXRgBp | 34.Bb3 | INACCURACY | BLUNDER | +0.0 → -1.0 | +0.0 → -2.2 |
| lichess-ahYXRgBp | 34...Qxb2 | INACCURACY | BLUNDER | -1.0 → -0.3 | -2.2 → -0.3 |
| lichess-ahYXRgBp | 36...Qd1 | MISTAKE | BLUNDER | -2.1 → -0.4 | -2.6 → -0.5 |
| lichess-ahYXRgBp | 37...Qg4+ | MISTAKE | BLUNDER | -4.3 → -2.1 | -7.7 → -2.6 |
| lichess-ahYXRgBp | 53...Kg5 | BLUNDER | MISTAKE | -5.8 → -2.4 | -7.1 → -3.6 |
| lichess-ahYXRgBp | 68...Rd5 | MISTAKE | - | -5.8 → -3.2 | -7.0 → -5.9 |
| lichess-ahYXRgBp | 69...Ke2 | INACCURACY | MISTAKE | -5.7 → -4.4 | -6.6 → -3.7 |
| lichess-ahYXRgBp | 73.Rc2 | GOOD | MISTAKE | -6.7 → -8.4 | -9.1 → mate in 8 |
| lichess-ahYXRgBp | 80...Qe2 | MISTAKE | INACCURACY | mate in 3 → -5.8 | mate in 3 → -16.7 |
| lichess-fBjlu0qV | 7...Rb8 | MISTAKE | BLUNDER | +1.1 → +2.4 | +0.9 → +3.0 |
| lichess-fBjlu0qV | 9...Nb8 | INACCURACY | MISTAKE | +2.9 → +4.2 | +3.4 → +5.4 |
| lichess-fBjlu0qV | 16...f5 | MISTAKE | INACCURACY | +3.7 → +6.0 | +5.4 → +7.4 |
| lichess-fDRgcj6S | 21...c6 | INACCURACY | MISTAKE | -0.8 → +0.3 | -0.8 → +0.5 |
| lichess-fDRgcj6S | 25...Rxd6 | MISTAKE | BLUNDER | -0.1 → +1.3 | -0.2 → +1.9 |
| lichess-nJJIXhCI | 25...Rg8 | INACCURACY | MISTAKE | +2.1 → +3.4 | +2.4 → +4.4 |
| lichess-nJJIXhCI | 26.Nc3 | GOOD | MISTAKE | +3.4 → +2.7 | +4.4 → +2.7 |
| lichess-nJJIXhCI | 26...Re7 | INACCURACY | MISTAKE | +2.7 → +3.7 | +2.7 → +4.5 |
| lichess-nJJIXhCI | 27.Ne4 | MISTAKE | BLUNDER | +3.7 → +1.8 | +4.5 → +1.7 |
| lichess-nJJIXhCI | 28...e5 | INACCURACY | MISTAKE | +1.5 → +2.6 | +1.6 → +3.4 |
| lichess-nJJIXhCI | 38...Qd4 | MISTAKE | BLUNDER | +5.3 → mate in 4 | +7.0 → mate in 4 |
| lichess-q7ymIJFQ | 7...b6 | BLUNDER | MISTAKE | -0.9 → +1.0 | -0.8 → +0.7 |
| lichess-q7ymIJFQ | 9.Bf4 | MISTAKE | BLUNDER | +1.1 → -0.5 | +1.5 → -0.2 |
| lichess-q7ymIJFQ | 9...d6 | MISTAKE | INACCURACY | -0.5 → +0.6 | -0.2 → +0.5 |
| lichess-q7ymIJFQ | 14...O-O | INACCURACY | MISTAKE | -4.3 → -3.3 | -6.1 → -3.8 |
| lichess-q7ymIJFQ | 16...h6 | MISTAKE | BLUNDER | -3.9 → -2.2 | -6.0 → -2.8 |
| lichess-q7ymIJFQ | 17.Re1 | INACCURACY | MISTAKE | -2.2 → -3.5 | -2.8 → -5.2 |
| lichess-q7ymIJFQ | 20...Qc5 | MISTAKE | INACCURACY | -1.2 → +0.0 | -1.1 → +0.0 |
| lichess-vyPxPj0e | 11.Bg5 | INACCURACY | MISTAKE | +0.8 → -0.2 | +0.9 → -0.3 |

## LLM output (anthropic/claude-haiku-5.5)

### Ungrounded citations (L2)

- platform-2: `Kd7` at ply any in “Kc6 throws away a forced mate in 5 and the evaluation drops to 0.0, leading to a draw. 63.Qb6 keeps the attack alive: after Kd7 the queen gives check on a7 and continues pressing for mate.”

### Spot check (L7)

Read each game and judge whether it is accurate and useful. L7 passes at 4 of 5.

#### lichess-2Rt3Z9M5

White began with a small advantage, but 6.Bxf7+ was a mistake that turned the evaluation against White. Black kept the edge through the middlegame. The turning point was the blunder 16.Qe2, which dropped White's evaluation from -1.2 to -3.8. Black won after 27.Rfd1 allowed a forced mate.

- 6.Bxf7+ (MISTAKE, best O-O): Taking the pawn with check wins material but misplaces the bishop and leaves the king exposed, swinging the evaluation from +0.5 to -1.1. Castling first with 6.O-O secures the king and keeps development intact.
- 14...Bc5 (MISTAKE, best b6): This bishop retreat gave up part of Black's advantage, moving the evaluation from -2.7 to -1.2 in White's favour. The better 14...b6 keeps the position solid and maintains Black's edge.
- 16.Qe2 (BLUNDER, best Qd6): This queen retreat was a blunder that dropped White's evaluation from -1.2 to -3.8 and decided the game. The better 16.Qd6 keeps the queen active and the position defensible.
- 27.Rfd1 (INACCURACY, best Re6): The position was already lost at -6.7, and this move allowed a forced mate in nine. The best resistance was 27.Re6, which trades rooks on e6 and prolongs the defence.

#### lichess-5QsQ8Cp3

White gained an edge early after Black's pawn advances on c4 and d3 loosened Black's position. White then erred with 13.Bb5+, but the decisive moment came when Black played 13...Ke7??, allowing checkmate in one. White won by checkmate.

- 8...c4 (MISTAKE, best Be7): Pushing the c-pawn loosens Black's queenside and gives White time to attack. The evaluation jumped from +0.2 to +1.6. Developing the bishop with 8...Be7 kept the position solid.
- 9...d3 (MISTAKE, best Qc7): This pawn push goes deep into White's camp, where it can be attacked and does not help Black's development. The evaluation rose from +1.6 to +3.0. 9...Qc7 keeps the queen active and supports the position.
- 10.Bxd3 (INACCURACY, best cxd3): Capturing with the bishop gives up White's central structure, and the evaluation fell from +3.0 to +1.9. Recapturing with the c-pawn, 10.cxd3, keeps the pawns together and controls the center.
- 13.Bb5+ (MISTAKE, best Nc3): The check loosened White's position and let the evaluation fall from +2.0 to +0.8. Developing the knight with 13.Nc3 keeps pressure on Black and maintains White's advantage.
- 13...Ke7 (BLUNDER, best Nc6): Moving the king into the open walks into a checkmate in one, the decisive blunder. The evaluation went from +0.8 to mate. 13...Nc6 develops the knight and keeps the king safe.

#### lichess-Lm55Fufr

Black played well early, but the position was roughly equal until 10...Bd6??, which allowed White a large advantage (from -0.1 to +3.3). White kept the advantage, and after further inaccuracies from both sides, White won by checkmate.

- 9.Bd3 (MISTAKE, best Nxd5): This is a mistake that drops the evaluation from +1.2 to -0.1. 9.Nxd5 was better, winning the pawn and keeping White's edge. Bd3 lets Black trade off the bishop and equalise.
- 9...e6 (BLUNDER, best Bxd3): This is a blunder that gives White a clear advantage (from -0.1 to +2.4). 9...Bxd3 was best, trading the bishop that White had placed on d3 and removing White's attacking piece.
- 10.O-O (BLUNDER, best Bxf5): This is a blunder that lets Black recover (from +2.4 to -0.1). 10.Bxf5 was best, winning a pawn immediately. Castling ignores the attacked f5 pawn and gives Black counterplay.
- 10...Bd6 (BLUNDER, best Bxd3): This is the turning point, moving the evaluation from -0.1 to +3.3 for White. 10...Bxd3 was best, trading bishops and keeping the position level. Bd6 leaves the position open for White's pieces to attack.
- 12...Be7 (MISTAKE, best Kf8): This is a mistake that worsens Black's position (from +3.5 to +6.0). 12...Kf8 was better, moving the king off the open central files. Be7 does not defend against White's attack.

#### lichess-Q8Kni4jL

Black's 4...e5 was a blunder that handed White a large advantage, moving the evaluation from +0.7 to +5.5. Several further errors followed on both sides, and White converted the advantage to win the game.

- 4...e5 (BLUNDER, best Bg7): Pushing e5 opens the centre while White can still capture on e5 and gain lines against Black's king. Bg7 instead develops the bishop, keeps the position closed and holds the evaluation near +0.7.
- 5.Nf3 (BLUNDER, best dxe5): Retreating the knight lets Black consolidate instead of recapturing. dxe5 wins back the pawn immediately and keeps White's large advantage. Nf3 reduces the evaluation from +5.5 to +1.5.
- 5...Bg4 (BLUNDER, best h6): Pinning the knight with the bishop looks active but misses White's strong central reply, and the evaluation rises to +5.4. h6 first secures a retreat square for the bishop and keeps Black's position stable.
- 7.Nd5 (BLUNDER, best dxe5): Jumping the knight to d5 leaves the central tension unresolved and lets Black untangle, cutting White's edge from +4.7 to +0.4. dxe5 recaptures at once, keeps the initiative and keeps the pressure on Black's queen and king.
- 9...Bxf3 (BLUNDER, best exd4): Trading the bishop for the knight gives up the bishop pair and lets White open the position, pushing the evaluation to +4.2. exd4 captures the pawn instead, keeps the balance and avoids giving White this simplification.

#### lichess-g3bLb9fX

A quiet game that ended in a draw. White made one inaccuracy and no mistakes or blunders, and Black had no inaccuracies. The turning point came at move 11, when White's 11.Qe2 let the evaluation slide from -0.2 to -0.8 in Black's favour. The game was drawn.

- 11.Qe2 (INACCURACY, best Nxe5): This was an inaccuracy. It moved the queen without addressing the central tension, and the evaluation fell from -0.2 to -0.8 for White. 11.Nxe5 was best: it wins the e5 pawn immediately and keeps the position balanced.
