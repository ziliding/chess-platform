# Engine eval report

Stockfish 17.1, depth 16, no time cap, 28 games. Regenerate with `python -m eval.run`.

| # | Metric | Result | Pass | Status |
| --- | --- | --- | --- | --- |
| E1 | Completion | 28/28 COMPLETED | every game COMPLETED, 0 worker errors | PASS |
| E2 | Determinism | identical moves in both runs | a second run gives byte-identical moves for every game | PASS |
| E3 | Serious errors (MISTAKE or BLUNDER) vs Lichess | recall 0.84 (162/194), precision 0.86 (162/188) | recall >= 0.80 (was 0.85, lowered with sign-off 2026-10-27) and precision >= 0.80 | PASS |
| E4 | Blunder agreement | 78% of 125 Lichess blunders are ours; 2 labeled BEST or GOOD | >= 75%, and at most 2 labeled BEST or GOOD (was 1, raised with sign-off 2026-10-27) | PASS |
| E5 | Eval agreement | median |cp difference| 15 over 1025 positions | <= 50, where both are non-mate and |cp| <= 500 | PASS |
| E6 | Latency | p95 job time 22.8s | p95 <= 60 s (target <= 30 s) | PASS |

## Games

| Game | Plies | Seconds | Our serious errors | Lichess serious errors |
| --- | --- | --- | --- | --- |
| lichess-0z3mP6cN | 60 | 8.8 | 6 | 6 |
| lichess-2Rt3Z9M5 | 64 | 11.0 | 3 | 4 |
| lichess-3m5zv1Vk | 39 | 5.3 | 2 | 3 |
| lichess-4EMSkHTu | 62 | 14.5 | 14 | 11 |
| lichess-5QsQ8Cp3 | 27 | 9.3 | 4 | 4 |
| lichess-8Pw6ookq | 46 | 10.6 | 6 | 6 |
| lichess-9izSqUo9 | 101 | 20.9 | 10 | 9 |
| lichess-AJOqh22R | 95 | 8.1 | 4 | 8 |
| lichess-BD5ftnrR | 125 | 11.3 | 8 | 5 |
| lichess-IEzKEvcT | 71 | 7.9 | 2 | 4 |
| lichess-Lm55Fufr | 173 | 15.9 | 6 | 5 |
| lichess-P3ZITVxp | 38 | 7.4 | 8 | 7 |
| lichess-Q8Kni4jL | 71 | 6.4 | 11 | 11 |
| lichess-QjIjWhJC | 95 | 19.6 | 5 | 4 |
| lichess-SZVqf5Ii | 59 | 16.8 | 17 | 17 |
| lichess-Wwylp8rn | 128 | 23.4 | 10 | 12 |
| lichess-ZjNGZsEJ | 57 | 15.9 | 7 | 7 |
| lichess-ahYXRgBp | 188 | 30.3 | 29 | 29 |
| lichess-fBjlu0qV | 91 | 14.5 | 3 | 3 |
| lichess-fDRgcj6S | 57 | 17.6 | 6 | 7 |
| lichess-g3bLb9fX | 24 | 6.9 | 0 | 0 |
| lichess-haNWbocW | 23 | 6.9 | 4 | 4 |
| lichess-nJJIXhCI | 81 | 12.8 | 5 | 9 |
| lichess-q7ymIJFQ | 43 | 13.4 | 13 | 13 |
| lichess-vyPxPj0e | 34 | 12.1 | 5 | 6 |
| platform-1 | 95 | 8.4 | 4 | - |
| platform-2 | 125 | 12.5 | 8 | - |
| platform-3 | 101 | 21.7 | 10 | - |

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
