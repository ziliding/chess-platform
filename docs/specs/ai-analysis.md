# AI game analysis

**Status:** Approved. Phase 1 is implemented on `feat/ai-analysis-phase-1` (see "Phase 1 as built" in §14); phases 2–4 are not started.
**Branch:** `feat/ai-analysis`
**Date:** 2026-10-26

Players can request an analysis of an archived game. They get an evaluation for every move, a list of mistakes with
the better move, and a short summary. Stockfish provides the evaluations. In phase 4, an LLM explains them. The analysis
runs in a separate, stateless Python service. Spring stays the owner of games, users, auth and persistence.

---

## 1. Decisions

| Topic                 | Decision                                                                                                                                                                                    |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Service split         | A new FastAPI service in `analysis/`. It is stateless and only analyzes. Spring owns games, users, auth and the results in Postgres.                                                        |
| Transport             | Jobs and results go over RabbitMQ (AMQP). Results reach the browser over the existing STOMP WebSocket.                                                                                      |
| Chess logic in Python | `python-chess`, with Stockfish over UCI. It does not use the Java rules engine.                                                                                                             |
| Engine budget         | Depth 16, `Threads=1`, `Hash=64` MB, with a 2 s cap per position. That is about 10–20 s for a 40-move game. Job deadline: 3 min, including queue wait.                                      |
| LLM                   | Phase 4. The client is any OpenAI-compatible endpoint (`base_url`, `model`, `api_key`). Dev uses a hosted key. CI uses a fake server. With no LLM configured, the template summary is used. |
| Trigger               | On request only. Nothing runs automatically after a game ends.                                                                                                                              |
| Who may request       | Only the two players of the match, with at most 3 queued jobs per user.                                                                                                                     |
| Who may view          | Any authenticated user. This matches `GET /api/games/{id}/pgn` today.                                                                                                                       |
| UI                    | A new review page, `/games/:matchId/analysis`, opened from History. It has a board you can step through, an annotated move list, the mistakes with the best move, and the summary.          |

### Assumptions (defaults; say if any are wrong)

- **Only archived matches can be analyzed.** Analysis is keyed on `match_records.id`. Live games, aborted games, and
  the planned guest casual games are never archived, so they cannot be analyzed.
- **One analysis per match.** A new engine version does not trigger re-analysis. If that is ever needed, add
  `analyzer_version` to the unique key.
- **The job topology shares RabbitMQ's default vhost `/` with the STOMP relay.** The `analysis.*` names cannot clash
  with the relay's use of `amq.topic`.
- **The eval reference is Lichess games.** The eval uses Lichess games that carry Lichess's own computer analysis.
  The Lichess database is CC0. Downloading them needs your OK at the start of phase 3.

### Out of scope

The following are not part of this work:

- automatic analysis after each game;
- an accuracy % score;
- an opening explorer;
- an eval graph (it can come after phase 4);
- separate RabbitMQ users per service;
- DLQ-depth metrics and alerting;
- localized summaries.

---

## 2. Repo facts this design depends on

1. **RabbitMQ only speaks STOMP today.** It is used through Spring's `enableStompBrokerRelay`. The pom has no
   `spring-boot-starter-amqp`. `compose.yaml` and `ContainersConfig.rabbitMqContainer()` expose only 61613 and 15672.
   Phase 1 adds the AMQP starter, port 5672 in compose and in the test container, and `SPRING_RABBITMQ_*` settings.
2. **The live game ID is not the match ID.** `MatchService.createGame` uses `UUID.randomUUID()` for the Redis game.
   `MatchRecord` gets its own UUIDv7 from `BaseEntity`. The Redis game expires one minute after it ends. So analysis
   reads the PGN from `match_records.pgn`, keyed on the match ID. The existing `/api/games/{id}/pgn` already takes a
   match ID, and the new endpoints follow the same convention.
3. **The stored PGN is `Game.getNotation()`.** It has non-standard tags (`StartTime`, `EndTime`), SAN movetext from the
   standard starting position, and a result token. `python-chess` reads this format. The contract fixtures include
   PGNs produced by the Java formatter so that this compatibility stays tested.
4. **`/topic/*` subscriptions have no authorization.** `/ws/**` is `permitAll`, and there is no message-level security.
   Analysis results are pushed to user destinations (`/user/topic/analysis`). Cross-instance user delivery is already
   configured (`setUserDestinationBroadcast`) and tested in `WebSocketRelayIT`.
5. **The frontend's `WebSocketService` is one client scoped to a game.** Connecting sends a `join` for that game. The
   analysis page needs its own small STOMP client that only subscribes to `/user/topic/analysis`.
6. **There is already a multi-instance sweeper pattern.** `GameTimeoutSweeper` runs on every instance, and an atomic
   check makes each game end exactly once. The analysis sweeper copies this pattern and uses a conditional `UPDATE`.
7. **The build gates are:**
   - JaCoCo line coverage of at least 80% on the bundle;
   - Error Prone with NullAway (JSpecify, so `@Nullable` is needed from the start);
   - Spotless (Palantir format);
   - Flyway with `validate-migration-naming` and `ddl-auto: validate`. The next migration is `V9__`.
8. **The repo uses Jackson 3** (`tools.jackson`). Spring AMQP's JSON converter normally picks the target type from a
   `__TypeId__` header, which Python will not send. The listener must infer the type from its method parameter.

---

## 3. Architecture

```
Browser ── POST /api/games/{matchId}/analysis ──▶ Spring (any instance)
   ▲                                               │ 1 INSERT game_analyses (QUEUED, jobId, deadline) → Postgres, commit
   │                                               │ 2 publish AnalysisRequest v1 (confirmed) ──▶ exchange `analysis` ─▶ queue analysis.requests
   │                                                                                                         │ prefetch 1
   │                                                                              Python worker (FastAPI + aio-pika)
   │                                                                              python-chess + Stockfish (+ LLM, phase 4)
   │                                                                                                         │
   │                                               ┌── queue analysis.results ◀── exchange `analysis` ◀──────┘ 3 publish AnalysisResult v1 (confirmed), then ack the request
   │                                               ▼
   │                                    Spring @RabbitListener: 4 conditional UPDATE by jobId → COMPLETED | FAILED
   └──── /user/topic/analysis (STOMP relay) ◀──── 5 convertAndSendToUser(both players, AnalysisResponse)
```

**Spring** checks auth and idempotency, owns the queue topology, persists results, runs the timeout sweeper, and
pushes updates over the WebSocket.

**The Python service** turns a PGN into analysis. It has no database and no shared state, so one job is a pure
function of its input message. That makes redelivery safe. Scale it by running more replicas.

**FastAPI's role** is the process host. Its lifespan starts the AMQP consumer and the Stockfish process, and it serves
`/healthz` (liveness) and `/readyz`. `/readyz` returns 200 only when the AMQP channel is open, the consumer is
registered, and Stockfish answers `isready`. The port stays internal: the gateway does not route to it.

### Layout of `analysis/`

```
analysis/
  pyproject.toml, uv.lock     # uv; Python 3.15 (`requires-python = ">=3.15"`); fastapi, aio-pika, python-chess, pydantic (+ openai in phase 4)
  Dockerfile                  # python:3.15-slim-trixie + Debian `stockfish`; non-root; HEALTHCHECK on /readyz
  src/analysis/
    contracts.py              # Pydantic v1 models
    engine.py                 # Stockfish analysis, classification, template summary (pure apart from the engine)
    worker.py                 # consume → analyze → publish → ack; error → result-code mapping
    main.py                   # FastAPI app, lifespan, health endpoints
    llm.py                    # phase 4
  tests/
  eval/                       # phase 3
contracts/analysis/v1/        # golden JSON fixtures shared by the Java and Python tests (repo root)
```

Tooling: ruff (lint and format), pyrefly (type checking, configured under `[tool.pyrefly]` in `pyproject.toml`), and pytest with coverage of at least 80%, which mirrors the Java gate.

---

## 4. Message contracts and versioning

### 4.1 Topology (Spring declares all of it)

Spring declares every exchange and queue through `Declarables`, which `RabbitAdmin` applies on connect. The Python
service only declares passively. It reports not-ready until `analysis.requests` exists, so it can never create a queue
with different arguments. Python test fixtures declare the same topology, copied from the table below.

| Name                    | Kind                     | Bound with                 | Arguments                                                                                                                                          |
| ----------------------- | ------------------------ | -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `analysis`              | direct exchange, durable | —                          | —                                                                                                                                                  |
| `analysis.dlx`          | direct exchange, durable | —                          | —                                                                                                                                                  |
| `analysis.requests`     | quorum queue             | `analysis` / `request`     | `x-dead-letter-exchange=analysis.dlx`, `x-dead-letter-routing-key=request`, `x-delivery-limit=3`, `x-max-length=1000`, `x-overflow=reject-publish` |
| `analysis.results`      | quorum queue             | `analysis` / `result`      | `x-dead-letter-exchange=analysis.dlx`, `x-dead-letter-routing-key=result`, `x-delivery-limit=5`                                                    |
| `analysis.requests.dlq` | quorum queue             | `analysis.dlx` / `request` | —                                                                                                                                                  |
| `analysis.results.dlq`  | quorum queue             | `analysis.dlx` / `result`  | —                                                                                                                                                  |

These arguments must be final in phase 1. Changing the arguments of an existing queue fails with
`PRECONDITION_FAILED`, and the only fix is to delete and recreate the queue.

**Message properties, for every message:**

- `content_type=application/json`
- `delivery_mode=2` (persistent)
- `message_id=<jobId>`
- results only: `correlation_id=<jobId>`

**Publishing.** Both sides publish with publisher confirms. Spring also publishes with `mandatory=true`, so an
unroutable message counts as a failed publish.

### 4.2 `AnalysisRequest` v1 (Spring → Python)

```json
{
  "schemaVersion": 1,
  "jobId": "0192c7a4-5b1e-7c3a-9f00-2d1c8e7b6a01",
  "matchId": "0192c7a1-0e44-7d2b-8a10-6f3e2b9c4d55",
  "pgn": "[StartTime \"...\"]\n...\n\n1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7#\n1-0",
  "deadline": "2026-10-26T12:03:00Z"
}
```

- **`jobId`** is generated by Spring, is the idempotency key, and changes on every retry. Results are matched on
  `jobId`, never on `matchId`.
- **`pgn`** is `match_records.pgn` exactly as stored. Python ignores the header tags. Usernames live in those tags and
  are never forwarded to the LLM (see §10).
- **`deadline`** is `requestedAt + 3 min`. Python drops a job whose deadline has already passed when it arrives.

### 4.3 `AnalysisResult` v1 (Python → Spring)

```json
{
  "schemaVersion": 1,
  "jobId": "0192c7a4-5b1e-7c3a-9f00-2d1c8e7b6a01",
  "matchId": "0192c7a1-0e44-7d2b-8a10-6f3e2b9c4d55",
  "status": "COMPLETED",
  "engine": { "name": "Stockfish 17.1", "depth": 16 },
  "initialEval": { "cp": 18, "mate": null },
  "moves": [
    {
      "ply": 1,
      "san": "e4",
      "uci": "e2e4",
      "eval": { "cp": 30, "mate": null },
      "bestSan": "e4",
      "bestUci": "e2e4",
      "winChanceLoss": 0.0,
      "classification": "BEST"
    },
    {
      "ply": 6,
      "san": "Nf6",
      "uci": "g8f6",
      "eval": { "cp": null, "mate": 1 },
      "bestSan": "g6",
      "bestUci": "g7g6",
      "winChanceLoss": 1.04,
      "classification": "BLUNDER"
    },
    {
      "ply": 7,
      "san": "Qxf7#",
      "uci": "h5f7",
      "eval": { "cp": null, "mate": 0 },
      "bestSan": "Qxf7#",
      "bestUci": "h5f7",
      "winChanceLoss": 0.0,
      "classification": "BEST"
    }
  ],
  "summary": {
    "text": "Black blundered on move 3 with 3...Nf6?? (3...g6 was best), allowing mate in one.",
    "source": "TEMPLATE"
  },
  "error": null
}
```

When the job fails, `status` is `"FAILED"`, `engine`, `initialEval`, `moves` and `summary` are `null`, and `error` is
set:

```json
{
  "schemaVersion": 1,
  "jobId": "...",
  "matchId": "...",
  "status": "FAILED",
  "engine": null,
  "initialEval": null,
  "moves": null,
  "summary": null,
  "error": { "code": "INVALID_PGN", "message": "illegal san: 'Nf9' in 3..." }
}
```

**Field rules:**

- **`eval`** is always from White's point of view. Exactly one of `cp` and `mate` is non-null. `mate: n > 0` means
  White mates in `n`. `mate: n < 0` means Black mates in `n`. `mate: 0` means the side to move after this ply is
  checkmated, which is python-chess's own convention. The eval is the position _after_ the move. The eval before ply
  `k` is the eval of ply `k-1`, or `initialEval` for ply 1.
- **Terminal positions are scored without the engine.** A checkmate is `mate: 0`. Stalemate, insufficient material
  and other draws are `cp: 0`.
- **`winChanceLoss`** is measured from the mover's point of view and is clamped to be at least 0. Winning chances are
  `2/(1+exp(-0.00368208·cp)) − 1`, which lies in [−1, 1]. Mate scores count as ±1, through python-chess
  `score(mate_score=10000)`.
- **`classification`** is one of `BEST`, `GOOD`, `INACCURACY`, `MISTAKE` or `BLUNDER`.
  - `BEST`: the played move equals the engine's best move.
  - Otherwise, by `winChanceLoss`: at least 0.3 is `BLUNDER`, at least 0.2 is `MISTAKE`, at least 0.1 is
    `INACCURACY`, and anything lower is `GOOD`.

  These thresholds are lila's (`Advice.scala`). Phase 1 checks them against the lila source before hardcoding them.

- **Mistakes** are the moves whose `classification` is `INACCURACY` or higher. They are not a separate list, so they
  can never disagree with the move data.
- **`summary.source`** is `TEMPLATE` (deterministic, from phase 1) or `LLM` (phase 4).

**Error codes:**

| Code                         | Meaning                                                                    | Permanent?                   |
| ---------------------------- | -------------------------------------------------------------------------- | ---------------------------- |
| `INVALID_PGN`                | The PGN does not parse, has no moves, or `python-chess` reports any errors | yes                          |
| `TOO_LONG`                   | The game is over 400 plies                                                 | yes                          |
| `UNSUPPORTED_SCHEMA_VERSION` | The worker does not know this `schemaVersion`                              | until the worker is upgraded |
| `ENGINE_ERROR`               | Stockfish crashed or hung twice, with a restart between the two tries      | no                           |
| `BUDGET_EXCEEDED`            | The analysis did not finish within the job's time budget                   | no                           |

Spring adds two codes of its own, which never come from Python: `TIMED_OUT` (set by the sweeper) and `UNAVAILABLE`
(the publish failed).

### 4.4 Versioning rules

1. **Every message carries `schemaVersion`** (an integer). A consumer handles the versions it knows. For any other
   version it replies `FAILED / UNSUPPORTED_SCHEMA_VERSION` if it can read `jobId`, and dead-letters the message if it
   cannot.
2. **Additive changes keep the version.** A new optional field is additive. Consumers must ignore unknown fields:
   - Pydantic uses `extra="ignore"`.
   - Jackson uses Spring Boot's mapper, which has `FAIL_ON_UNKNOWN_PROPERTIES` off. A contract test proves this.
3. **Breaking changes bump the version.** Breaking means removing or renaming a field, changing a field's type or
   meaning, or **adding an enum value** (Jackson rejects unknown enum constants). They roll out consumer-first:
   1. Deploy the consumer that accepts v1 and v2.
   2. Switch the producer to v2.
   3. Remove v1 support a release later.

   The version stays in the body, so queue and routing-key names stay the same.

4. **Wire format.** JSON is camelCase on the wire. Pydantic uses `alias_generator=to_camel, populate_by_name=True`.
   Java uses plain records.
5. **Phase 4 is the first real use of these rules.** It adds the optional `moves[].explanation` without changing the
   version.

### 4.5 How the contract is tested

`contracts/analysis/v1/` holds golden fixtures:

- `request.scholars-mate.json`
- `request.platform-pgn.json`, whose PGN comes from `Game.getNotation()`
- `result.completed.json`
- `result.failed.json`
- `result.completed.extra-field.json`, which has an unknown field

The tests on each side:

- **Java (`AnalysisContractTest`):**
  - Every `result.*` fixture deserializes into the result DTO.
  - The request DTO serializes to JSON equal to `request.*`, ignoring key order.
  - A test asserts that `request.platform-pgn.json` is what the Java formatter produces for a fixed `TestGames` game,
    so the fixture cannot go stale.
- **Python (`test_contracts.py`):**
  - Every fixture validates.
  - Each `result.*` fixture round-trips to equal JSON.
  - Analyzing the PGN in `request.platform-pgn.json` succeeds.

**The fixtures must run through the real converter.** The AMQP message converter is built from Boot's `JsonMapper`
bean, not from a default mapper of its own. `AnalysisContractTest` deserializes the fixtures through that converter
bean, so the unknown-field rule is tested on the path the listener actually uses. The AMQP IT also sends
`result.completed.extra-field.json` end to end.

The backend tests read the fixtures from `../contracts`. The backend Docker build already skips tests, so it does not
need them.

---

## 5. Job lifecycle and idempotency

### 5.1 States

```
          POST (player, service up)
(none) ──────────────────────────────▶ QUEUED ──result COMPLETED──▶ COMPLETED   (terminal; GET serves it forever)
                                         │ ├──result FAILED───────▶ FAILED
                                         │ └──deadline passed─────▶ FAILED (TIMED_OUT, by the sweeper)
                                         └──publish failed────────▶ FAILED (UNAVAILABLE)
FAILED ──POST (player)──▶ QUEUED with a new jobId and a new deadline
```

There is no RUNNING state. Spring cannot observe it without an extra message, and the UI does not need it.

### 5.2 Table: `V9__create_game_analyses.sql`

```sql
CREATE TABLE game_analyses
(
    id           uuid PRIMARY KEY,
    match_id     uuid        NOT NULL UNIQUE REFERENCES match_records (id) ON DELETE CASCADE,
    job_id       uuid        NOT NULL UNIQUE,
    requested_by uuid        REFERENCES users (id) ON DELETE SET NULL,
    status       varchar(10) NOT NULL,
    deadline     timestamptz NOT NULL,
    result       jsonb,
    error_code   varchar(30),
    completed_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,

    CHECK (status IN ('QUEUED', 'COMPLETED', 'FAILED')),
    CHECK ((status = 'COMPLETED') = (result IS NOT NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);

CREATE INDEX idx_game_analyses_queued_deadline ON game_analyses (deadline) WHERE status = 'QUEUED';
```

**Columns:**

- **`result`** holds the `engine`, `initialEval`, `moves` and `summary` parts of a completed result. It is stored as
  jsonb, and Spring never queries inside it. It is mapped with `@JdbcTypeCode(SqlTypes.JSON)`. If Hibernate's JSON
  mapper does not work with Jackson 3, the fallback is a `String` with an `AttributeConverter`.
- **The single row per match** is enforced by `UNIQUE (match_id)`, and `job_id` rotates on each retry. So a result
  from an earlier attempt can never overwrite the current one.

### 5.3 REST API

| Method and path                      | Who                        | Responses                                                                                                                                                                                                 |
| ------------------------------------ | -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /api/games/{matchId}/analysis`  | any authenticated user     | `200 AnalysisResponse`; `404` if there is no analysis or no match                                                                                                                                         |
| `POST /api/games/{matchId}/analysis` | either player of the match | `202` new or re-queued job; `200` already QUEUED or COMPLETED (no new job); `403` not a player; `404` no match; `429` the user already has 3 QUEUED jobs; `503` `ANALYSIS_UNAVAILABLE` or `ANALYSIS_BUSY` |

Errors use the existing `ProblemDetail` format.

`AnalysisResponse` is `{ matchId, status, errorCode, deadline, completedAt, engine, initialEval, moves,
summary }`. The same DTO is pushed over the WebSocket.

### 5.4 Request flow (`AnalysisService.request`)

1. Load the match, then check that the caller is a player (`403`).
2. If a row exists and is QUEUED or COMPLETED, return it with `200`. Nothing is published. This step runs before the
   rate limit, so a double-click still gets its existing job back.
3. Check that the caller has fewer than 3 jobs QUEUED (`429`). This is a soft limit: a race can let a user exceed it by
   one.
4. **Availability check.** Read `RabbitAdmin.getQueueInfo("analysis.requests")`. If it reports 0 consumers, return
   `503 ANALYSIS_UNAVAILABLE` and write no row.
5. Write the row (QUEUED, new `jobId`, `deadline = now + 3m`) and **commit**:
   - for a new match: `INSERT … ON CONFLICT (match_id) DO NOTHING`;
   - for a FAILED row: `UPDATE … WHERE match_id = ? AND status = 'FAILED'`.

   If either statement affects 0 rows, another request won the race, so re-read the row and return it with `200`.

6. **Publish after the commit.** If Spring published before committing, a fast result could arrive for a job that is
   not committed yet. The publish waits for the confirm, up to 5 s.
   - If the publish is nacked because the queue is full: `UPDATE … SET status='FAILED', error_code='UNAVAILABLE' WHERE
job_id=? AND status='QUEUED'`, then return `503 ANALYSIS_BUSY`.
   - If it is returned as unroutable or times out: the same update, then `503 ANALYSIS_UNAVAILABLE`.
   - A confirm timeout does not prove the message was lost, and its result may already have arrived. That is why the
     update keeps the `status='QUEUED'` guard. If it changes 0 rows, re-read the row and return it instead of a `503`.
7. Return `202` with the QUEUED response.

If the instance crashes between steps 5 and 6, the row stays QUEUED until the sweeper fails it with `TIMED_OUT`, and the
user can retry. A transactional outbox would close this gap, but this is a rare gap with a bounded, visible effect.

### 5.5 Result handling (`@RabbitListener(queues = "analysis.results")`)

1. The listener receives the deserialized `AnalysisResultMessage`. The converter infers the type from the method
   parameter.
2. It runs `UPDATE game_analyses SET status=?, result=?|error_code=?, completed_at=now() WHERE job_id=? AND
status='QUEUED'`.
3. If 0 rows change, the result is a duplicate, comes from an old `jobId`, or arrived after a timeout. The listener logs
   it at INFO and acks it.
4. If 1 row changes, it pushes the `AnalysisResponse` to both players' `/user/topic/analysis`.

### 5.6 Idempotency summary

| Situation                                                     | Result                                                                                                    |
| ------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Double-click, or two tabs POST at once                        | `UNIQUE (match_id)` and the conditional writes allow one job; both callers get the same job               |
| A request is delivered twice (crash before the ack)           | Python recomputes the same answer and publishes it twice; Spring applies the first and ignores the second |
| A result arrives after the sweeper marked the job `TIMED_OUT` | Ignored (status is no longer QUEUED). The user's retry has a new `jobId`                                  |
| A result arrives for a superseded `jobId`                     | Ignored (`job_id` no longer matches)                                                                      |
| Several instances run the sweeper                             | The conditional `UPDATE … WHERE status='QUEUED' AND deadline < now()` fails each job exactly once         |

---

## 6. Timeouts, retries, DLQ

### 6.1 Timeouts (three layers, from innermost to outermost)

| Layer                   | Value                                                 | What happens on expiry                                                                             |
| ----------------------- | ----------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Stockfish, per position | `Limit(depth=16, time=2.0)`                           | The search stops at the 2 s cap. It returns the best eval found so far, and the job is not failed. |
| Python, per job         | `min(deadline − now, 120 s)`, using `asyncio.timeout` | `FAILED / BUDGET_EXCEEDED`                                                                         |
| Python, on arrival      | `deadline` already passed                             | Acked and dropped (logged), because Spring has timed it out or will                                |
| LLM call (phase 4)      | 20 s per call, 1 retry, 30 s in total                 | The template summary is used and the job still completes                                           |
| Spring publish confirm  | 5 s                                                   | `503`, and the row becomes `FAILED / UNAVAILABLE`                                                  |
| Spring job deadline     | 3 min after the request; the sweeper runs every 15 s  | `FAILED / TIMED_OUT`, pushed to the players                                                        |

### 6.2 Failures, retries and dead-lettering

| Failure                                                          | Handled by | Behaviour                                                                                                                                                                                   |
| ---------------------------------------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The request body is not JSON, or has no readable `jobId`         | Python     | `reject(requeue=False)`, so it goes to `analysis.requests.dlq` and is logged at ERROR. The row times out.                                                                                   |
| Unknown `schemaVersion`, bad PGN, or a game that is too long     | Python     | Python publishes `FAILED` with the code and acks the request. It is not dead-lettered, because the user gets a specific answer.                                                             |
| Stockfish crashes or hangs                                       | Python     | Python restarts the engine and retries the job once, in process. If it fails again, it publishes `FAILED / ENGINE_ERROR`.                                                                   |
| The worker process dies mid-job (OOM, kill)                      | RabbitMQ   | The unacked request is redelivered to another worker. After `x-delivery-limit=3`, it goes to `analysis.requests.dlq`.                                                                       |
| Publishing the result fails                                      | Python     | The request is not acked. The channel closes and RabbitMQ redelivers it, so the job is recomputed.                                                                                          |
| A result is malformed (conversion fails)                         | Spring     | `AmqpRejectAndDontRequeueException`, so it goes to `analysis.results.dlq`. The row times out.                                                                                               |
| Postgres is down while a result is consumed                      | Spring     | Stateless listener retry: 3 attempts with backoff of 1 s, 2 s and 4 s. Then the message is rejected without requeue and goes to `analysis.results.dlq`. `x-delivery-limit=5` is a backstop. |
| The broker is down, or the queue is full, when a request arrives | Spring     | `503`, with the row set to `FAILED / UNAVAILABLE` (§5.4).                                                                                                                                   |

Spring settings:

```yaml
spring.rabbitmq:
  publisher-confirm-type: correlated
  publisher-returns: true
  template.mandatory: true
  listener.simple:
    default-requeue-rejected: false
    retry: { enabled: true, max-attempts: 3, initial-interval: 1s, multiplier: 2 }
```

On the Python side:

- **Ordering.** The worker acks a request only after the broker confirms the result publish. If anything fails after
  analysis starts, the message is redelivered.
- **Concurrency.** `prefetch_count=1` per worker, because Stockfish is CPU-bound.
- **Shutdown.** On SIGTERM, the worker stops consuming and finishes the current job. Compose sets
  `stop_grace_period: 60s` for this.

### 6.3 Operating the DLQs

The DLQs are for **inspection only**. They have no automatic consumer and no replay; the user recovers by retrying.
You can browse them in the management UI on port 15672, and every dead-lettered message has an ERROR log line with its
`jobId`. Metrics and alerts on DLQ depth come later.

---

## 7. WebSocket delivery

- **Destination.** `convertAndSendToUser(username, "/topic/analysis", AnalysisResponse)` goes to both players of the
  match. The client subscribes to `/user/topic/analysis` and filters on `matchId`.
- **Source of truth.** The push is a notification. `GET` is authoritative.
  - **Ordering.** The page subscribes first and then calls GET. That way a result that lands between the two calls is
    not lost.
  - **Reconnects.** STOMP topics do not buffer, so every reconnect calls GET again.
- **The page never spins forever.** While the status is QUEUED, the page schedules one GET at `deadline + 20 s`. By
  then the sweeper has run, even if every push was lost.
- **Client.** It is a separate small STOMP client (a `subscribeUser` helper in `websocketService.ts`) that sends no
  game `join`. The game page is unchanged.

---

## 8. Degraded-mode UX

The board, move list and PGN replay always work, because they only need the PGN. Analysis only adds information to
them.

| Condition                                      | How it is detected                                       | API                                        | What the user sees                                                                             |
| ---------------------------------------------- | -------------------------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| Not analyzed yet                               | GET returns 404                                          | —                                          | An **Analyze game** button. The page cannot tell players from spectators, so a spectator who clicks gets the 403 message.              |
| Queued or running                              | `status=QUEUED`                                          | —                                          | "Analyzing… usually takes under 30 s", with the board usable                                   |
| Analysis service down                          | 0 consumers on `analysis.requests`                       | `503 ANALYSIS_UNAVAILABLE`, no row written | "Analysis is temporarily unavailable. Try again in a few minutes." The button stays enabled.   |
| Broker down, or publish fails                  | Publish nacked, returned or timed out                    | `503 ANALYSIS_UNAVAILABLE`                 | Same as above                                                                                  |
| Backlog full                                   | The queue is at `x-max-length`, so the publish is nacked | `503 ANALYSIS_BUSY`                        | "The analysis queue is busy. Try again shortly."                                               |
| The service died or stalled mid-job            | The sweeper fails the job at the deadline                | push and GET return `FAILED / TIMED_OUT`   | "Analysis took too long." **Retry** button                                                     |
| Engine error or budget exceeded                | Python sends FAILED                                      | `FAILED / ENGINE_ERROR`, `BUDGET_EXCEEDED` | "Analysis failed." **Retry** button                                                            |
| The game cannot be analyzed                    | Python sends FAILED                                      | `FAILED / INVALID_PGN`, `TOO_LONG`         | "This game can't be analyzed." No retry                                                        |
| LLM down, slow, or giving bad output (phase 4) | Python falls back                                        | `COMPLETED`, `summary.source=TEMPLATE`     | Everything is shown, with a small note: "AI explanation unavailable — showing engine summary." |
| WebSocket down                                 | STOMP client state                                       | —                                          | The QUEUED view stays. A reconnect, or the GET at the deadline, picks up the result.           |
| Rate limited                                   | 3 jobs already QUEUED                                    | `429`                                      | "You have 3 analyses running. Wait for one to finish."                                         |

---

## 9. Analysis algorithm (Python)

1. Validate the envelope: `schemaVersion` and `jobId`. Then check the deadline and parse the PGN with
   `chess.pgn.read_game`. If `game.errors` is not empty, or the game has no moves, reply `INVALID_PGN`. More than 400
   plies is `TOO_LONG`.
2. Start a new engine game, so the hash is fresh and the result is deterministic. Then analyze positions `0…N`, which
   is N+1 engine calls with MultiPV 1. Score terminal positions directly, without the engine.
3. For each ply `k`:
   - `bestUci` is the first move of the principal variation from position `k-1`;
   - `eval` is the score of position `k`;
   - `winChanceLoss` and `classification` follow §4.3.
4. Write the template summary. It is deterministic and only uses facts from step 3:
   - the counts of inaccuracies, mistakes and blunders for each side;
   - the turning point, which is the move with the largest `winChanceLoss`, shown with the best move and the eval
     before and after;
   - the result.

   For example: _"White: 1 inaccuracy, 0 mistakes, 0 blunders. Black: 0 inaccuracies, 1 mistake, 1 blunder. Turning
   point: 3...Nf6?? (3...g6 was best), from −0.2 to mate in 1. White won by checkmate."_

---

## 10. LLM explanations (phase 4)

- **Input.** The prompt gets only engine facts as JSON:
  - the side and move number;
  - the move played;
  - the best move;
  - the eval before and after;
  - the classification;
  - for each mistake, the first 4 moves of the engine's principal variation.

  It gets **no PGN headers and no usernames**. Usernames are user-controlled, so leaving them out removes that
  prompt-injection vector.

- **Output.** The model returns `{ "summary": str, "explanations": [{ "ply": int, "text": str }] }` as JSON. It explains
  at most 5 mistakes, in order of `winChanceLoss`, always including every blunder. Pydantic validates the output.
  - If it is invalid, the worker makes one repair call with the validation error.
  - If that also fails, the worker uses the template summary.
- **Contract change.** This adds `moves[].explanation` (optional) and `summary.source="LLM"`. It is additive, so the
  version stays v1.
- **Calls and errors.**
  - The worker uses the `openai` SDK with `base_url` configured, with the timeouts in §6.1.
  - It retries once on 429, 5xx or a timeout.
  - A circuit breaker skips the LLM for 60 s after 3 failures in a row, so jobs do not each wait 30 s while the
    provider is down.
- **Rendering.** The UI renders the output as plain text only, with no markdown or HTML.
- **Configuration:**
  - `LLM_BASE_URL`, `LLM_MODEL` and `LLM_API_KEY` configure the client.
  - If they are unset, the LLM is disabled.
  - Dev uses a hosted key.
  - CI uses a fake OpenAI-compatible server inside the tests.

---

## 11. Test strategy

| Layer                | What                                                                                                                                                                                                                                                                                                                                                                                                                                                          | Where and how                                                                                                                                 |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Python unit          | Win chances, thresholds, mate and terminal scoring, POV sign, template summary; table-driven                                                                                                                                                                                                                                                                                                                                                                  | `pytest`, no I/O                                                                                                                              |
| Python contract      | Golden fixtures validate and round-trip; unknown fields are ignored; an unknown `schemaVersion` gives the right error                                                                                                                                                                                                                                                                                                                                         | `pytest`                                                                                                                                      |
| Python engine        | Real Stockfish on short known games. Scholar's mate: `3...Nf6` is a BLUNDER with `bestSan` `g6` or `Qe7`/`Qf6`. A stalemate game; a promotion game; the platform PGN                                                                                                                                                                                                                                                                                          | `pytest`, with the distribution's Stockfish. Tests assert robust properties, not exact centipawns |
| Python worker        | Request → result on a real broker; `INVALID_PGN` → FAILED result; non-JSON → DLQ; expired deadline → dropped. Phase 2 adds: engine crash → retry → `ENGINE_ERROR`; worker killed 3× → DLQ                                                                                                                                                                                                                                                                     | `testcontainers` RabbitMQ (`rabbitmq:4.1-management`, the same image as compose)                                                              |
| Python LLM (phase 4) | Valid output; invalid JSON → repair → fallback; timeout → fallback; circuit breaker; usernames never in the prompt                                                                                                                                                                                                                                                                                                                                            | A fake OpenAI-compatible server in the test (`httpx.MockTransport`)                                                                           |
| Java unit            | `AnalysisService` decisions: 403, 429, existing row → 200, 0 consumers → 503, nack → 503 and FAILED; result handling ignores stale and duplicate results; sweeper                                                                                                                                                                                                                                                                                             | Mockito, like `MatchServiceTest`                                                                                                              |
| Java WebMvc          | The status and ProblemDetail mapping for both endpoints; auth required                                                                                                                                                                                                                                                                                                                                                                                        | `@WebMvcTest`, like `GameControllerWebMvcTest`                                                                                                |
| Java repository IT   | The unique `match_id`; the conditional updates return 0 or 1 rows; jsonb round-trip; check constraints                                                                                                                                                                                                                                                                                                                                                        | Postgres Testcontainers (`RepositoryTest`)                                                                                                    |
| Java contract        | §4.5, through the real AMQP converter bean                                                                                                                                                                                                                                                                                                                                                                                                                    | A Spring test slice that loads only the converter config                                                                                      |
| Java AMQP IT         | **Happy path:** POST → a fake responder in the test consumes `analysis.requests` and replies with `result.completed.json` → the row is COMPLETED → a STOMP client on `/user/topic/analysis` receives it. **Failure path:** no responder → POST returns 503; a responder that never replies → the sweeper fails the job with `TIMED_OUT` and it is pushed; a malformed result → `analysis.results.dlq`                                                         | `@IntegrationTest`, with `ContainersConfig` extended for port 5672                                                                            |
| Frontend             | The analysis page's state logic (each row of §8); subscribe before GET; GET again on reconnect and at the deadline                                                                                                                                                                                                                                                                                                                                            | vitest, like `websocketService.test.ts`                                                                                                       |
| End to end (phase 2) | The smoke scenario already ends with a logged-in player whose game has just been archived, so its last step requests analysis of that match. `LOADTEST_EXPECT_ANALYSIS` sets what that step checks. **Happy path:** with `completed`, it polls GET until the analysis is COMPLETED (up to 60 s). **Failure path:** a second CI step runs `docker compose stop analysis`, then runs the smoke again with `unavailable`, and expects `503 ANALYSIS_UNAVAILABLE` | `ChessGameSimulation` and `.github/workflows/ci.yaml`, `loadtest-smoke` job                                                                   |

**CI** gets a new `analysis` job. It runs on the runner, like `uv run pytest` locally: `uv sync --frozen`, then `ruff check`,
`ruff format --check`, `pyrefly check` and `pytest --cov-fail-under=80`, with the distribution's `stockfish` package installed by
apt. The worker tests start their own RabbitMQ with testcontainers, so CI exercises the same path as a developer's
machine. The engine
eval joins the job in phase 3. The existing backend tests must keep passing once the AMQP starter is on the classpath.

---

## 12. Eval

This eval has two goals. It should show that the labels are right, and catch regressions in the classification code
and the prompts. The pass criteria are **fixed before the first run**. If a run misses, we find the cause. Either the
code gets fixed, or a threshold changes with your explicit sign-off, which is recorded in the report. Thresholds are
never relaxed silently.

### 12.1 Dataset (`analysis/eval/games/`, 28 games)

- **25 Lichess games** that have Lichess computer analysis, exported with their `[%eval]` and judgment annotations
  (CC0). The selection is fixed and committed:
  - ratings from 800 to 2500, and bullet through classical;
  - at least 3 checkmates, 3 resignations, 2 timeouts, and 3 draws (repetition, stalemate, agreement);
  - at least 2 games under 20 moves, and at least 2 over 80 moves;
  - between them, at least one each of: castling on both sides, en passant, and underpromotion.
- **3 PGNs exported from this platform** by the Java formatter. They have no reference labels and only test format
  compatibility and completion.

### 12.2 Engine metrics (phase 3, deterministic, gate the `analysis` CI job)

| #   | Metric                                                                                                                                | Pass                                                                                                  |
| --- | ------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| E1  | Completion                                                                                                                            | 28/28 games COMPLETED, 0 worker errors                                                                |
| E2  | Determinism: the eval runs depth-only (`ENGINE_POSITION_TIMEOUT=0`), because a time cap that fires makes the search non-deterministic | A second run gives byte-identical `moves` for every game                                              |
| E3  | Detection of serious errors (MISTAKE ∪ BLUNDER) against Lichess, matched by ply                                                       | recall ≥ 0.85 **and** precision ≥ 0.80                                                                |
| E4  | Blunder agreement                                                                                                                     | ≥ 75% of Lichess BLUNDERs are our BLUNDER; at most 1 Lichess BLUNDER labeled BEST or GOOD by us       |
| E5  | Eval agreement                                                                                                                        | median \|our cp − Lichess cp\| ≤ 50, over positions where both evals are non-mate and \|cp\| ≤ 500    |
| E6  | Latency                                                                                                                               | p95 job time ≤ 60 s on the CI runner (gate); the actual p95 is also reported, with a target of ≤ 30 s |

### 12.3 LLM metrics (phase 4; run by hand or by `workflow_dispatch` with a hosted key, because it needs a secret)

| #   | Metric                                                                                                                                                                                  | Pass                                                                                |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| L1  | Schema validity                                                                                                                                                                         | ≥ 95% valid on the first call; 100% of jobs COMPLETED (fallback included)           |
| L2  | Grounding: every SAN move cited (found by regex, with an optional move number) must be the move played at that ply or the engine's best move there, with the right side and move number | ≥ 98% of all citations grounded, **and** ≥ 90% of games with no ungrounded citation |
| L3  | Turning point: the summary cites the move with the largest `winChanceLoss`                                                                                                              | ≥ 80% of the games that contain a BLUNDER                                           |
| L4  | Length                                                                                                                                                                                  | 100% of summaries ≤ 80 words, and every explanation ≤ 40 words                      |
| L5  | Latency and cost                                                                                                                                                                        | LLM-step p95 ≤ 15 s; tokens per game reported                                       |
| L6  | Checker self-test: the template summaries pass L2 at 100%                                                                                                                               | 100%                                                                                |
| L7  | Human spot check: you read 5 random games                                                                                                                                               | ≥ 4/5 judged "accurate and useful"                                                  |

L1–L6 are measured automatically and L7 is your manual check. The template summary is the baseline. There is no
LLM-as-judge.

**Output.** `uv run python -m eval.run [--llm]` writes `eval/report.json` and `eval/report.md` with every metric and
PASS or FAIL. Its exit code is non-zero if any gated metric fails.

---

## 13. Configuration and deployment

| Where                                                          | Added                                                                                                                                                                                                                                                                                                                        |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend/pom.xml`                                              | `spring-boot-starter-amqp`                                                                                                                                                                                                                                                                                                   |
| `application.yaml`                                             | the `spring.rabbitmq.*` settings in §6.2; `app.analysis.deadline: 3m`, `app.analysis.sweep-interval: 15s`, `app.analysis.max-queued-per-user: 3`                                                                                                                                                                             |
| `application-dev.yaml`, `.env.example`, `backend/.env.example` | `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD`                                                                                                                                                                                                                                                                                |
| `compose.yaml`                                                 | RabbitMQ publishes `5672:5672`. New `analysis` service (profile `app`): built from `./analysis`, depends on a healthy `rabbitmq`, `AMQP_URL`, `ENGINE_DEPTH=16`, `ENGINE_POSITION_TIMEOUT=2`, `JOB_BUDGET_SECONDS=120`, `LLM_*` (phase 4), healthcheck on `/readyz`, `stop_grace_period: 60s`. Scale with `deploy.replicas`. |
| `ContainersConfig`                                             | Exposes 5672 and registers `spring.rabbitmq.*`                                                                                                                                                                                                                                                                               |
| CI                                                             | The new `analysis` job; the `loadtest-smoke` job extended (phase 2)                                                                                                                                                                                                                                                          |
| README                                                         | An "AI analysis" section: architecture, how to run it, and the eval results table                                                                                                                                                                                                                                            |

---

## 14. Phases

Each phase is one PR into `main` and leaves CI green.

### Phase 1: a Stockfish-only end-to-end slice

This phase fixes the full contract and topology, and delivers the happy path plus the main failure paths.

- **Contracts:** the `contracts/analysis/v1/` fixtures, and the contract tests on both sides.
- **Python:**
  - `contracts.py`, `engine.py` (classification and template summary), `worker.py` and `main.py`, plus the
    Dockerfile;
  - the unit, contract, engine and worker tests: the happy path, `INVALID_PGN`, non-JSON → DLQ, and an expired
    deadline.
- **Spring:**
  - the AMQP starter and the topology (final arguments);
  - `V9` with the entity and repository;
  - `AnalysisService` (authorization, rate limit, availability check, commit then publish with confirm);
  - the result listener, the sweeper, both REST endpoints, and the user-destination push;
  - every Java test row in §11.
- **Frontend:**
  - the `/games/:matchId/analysis` page: board stepper, annotated move list, mistakes, and summary;
  - the History link;
  - every state in §8 except the LLM note;
  - vitest tests.
- **Infra:** compose (the `analysis` service and port 5672), the env templates, the CI `analysis` job, and the README.
- **Exit criteria:**
  - Run `docker compose --profile app up`, play a game, open the analysis, and see evals, mistakes and a summary within
    30 s.
  - After `docker compose stop analysis`, the page shows "temporarily unavailable".
  - All CI gates are green.

### Phase 2: hardening the failure paths

- **Python:**
  - an engine restart and in-process retry (→ `ENGINE_ERROR`);
  - the per-job budget (→ `BUDGET_EXCEEDED`);
  - the 400-ply cap (→ `TOO_LONG`);
  - graceful SIGTERM;
  - a test that kills the worker 3 times and expects the message in the DLQ.
- **Spring:**
  - a nack from the full queue → `503 ANALYSIS_BUSY`;
  - a broker outage at publish time → `503` with `FAILED / UNAVAILABLE`;
  - an outage in the middle of consuming a result → retry, then the results DLQ (tested);
  - tests that a late result and a duplicate result are both ignored.
- **Frontend:**
  - polishing the copy for every degraded state;
  - the GET at the deadline;
  - tests for the GET on reconnect.
- **CI:** the end-to-end smoke test with its failure step (§11, last row).

### Phase 3: engine eval

- Assemble the 28-game dataset (this needs your OK to download from Lichess).
- Write `eval/run.py` and the reports.
- Gate CI on E1–E6.
- Put the results in the README.

### Phase 4: LLM explanations and the LLM eval

- `llm.py`: the facts-only prompt, JSON output with one repair call, timeouts, retry, the circuit breaker, and the
  template fallback.
- The additive contract field `moves[].explanation`: update the fixtures, the Java record, and the UI (explanations and
  the LLM-unavailable note).
- Tests against the fake OpenAI-compatible server.
- The L1–L7 eval: a `workflow_dispatch` job with the hosted key as a secret, and the report committed.

---

### Phase 1 as built

Deviations from the draft above, and the §15 checks phase 1 settled:

- **`requestedAt` is not in `AnalysisResponse`.** Nothing needs it; `deadline` is enough.
- **Analyze button for everyone.** Whether the viewer is a player is not available to the page without a new
  endpoint, so the server's 403 is shown instead.
- **`GameAnalysis` is a read-only entity.** Every change is a conditional native statement in `GameAnalysisRepo`.
  The jsonb `result` is a `String` with `@JdbcTypeCode(SqlTypes.JSON)`; it round-trips (§15.2 settled, no fallback).
- **Converter (§15.1 settled).** `JacksonJsonMessageConverter(jsonMapper)` with `TypePrecedence.INFERRED`; the
  contract test reads every fixture through that bean, as the listener does.
- **Thresholds (§15.3, thresholds part settled).** 0.3, 0.2 and 0.1 match lila's `Advice.scala`. lila also has
  special rules for creating or losing a mate (`MateCreated`, `MateLost`, by centipawn range); this service
  counts a mate as +-1 winning chances instead. Phase 3 measures the difference.
- **Stockfish (§15.5 settled).** The `python:3.15-slim-trixie` image has Stockfish 17.1.
- **Python input check.** python-chess silently skips movetext it cannot tokenize (`2. Nf9`), so `INVALID_PGN` also
  covers a token count that differs from the moves read. Annotated PGN (comments, variations) skips this check.
- **`TOO_LONG` (400 plies) landed in phase 1**, since it is two lines next to the PGN parsing.
- **Not yet done, as planned:** per-job time budget, engine restart and retry, graceful SIGTERM, the end-to-end CI
  smoke test and the "queue full" and "broker down mid-consume" tests (phase 2).

## 15. Verify before implementing

These are things to check at the start of the phase that needs them:

1. **The Spring AMQP JSON converter.** Find the Jackson 3 converter class in Spring AMQP 4.x. Confirm that listener
   type inference works without a `__TypeId__` header (phase 1).
2. **Hibernate jsonb mapping with Jackson 3.** Check that it works. If it does not, use the `String` with
   `AttributeConverter` fallback (phase 1).
3. **Lichess.** Check the classification thresholds in lila's `Advice.scala` and the mate-handling differences. Check
   that the game export includes both `[%eval]` and the judgment glyphs (phase 1 for the thresholds, phase 3 for the
   export).
4. **RabbitMQ 4.1 quorum queues.** Check how `x-delivery-limit` counts consumer disconnects compared with nacks. This
   decides how the SIGTERM handler returns an unfinished job (phase 2).
5. **Stockfish in the image.** Check which version the Debian `stockfish` package in `python:3.15-slim-trixie` has. The
   results report it, and E2 needs the same version within a run (phase 1).
