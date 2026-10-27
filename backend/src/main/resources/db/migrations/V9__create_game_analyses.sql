-- One AI analysis per archived match. job_id changes on every retry, so a late result of an earlier attempt can
-- never overwrite the current one. `result` holds the engine, initialEval, moves and summary of a completed job.
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
CREATE INDEX idx_game_analyses_requested_by ON game_analyses (requested_by) WHERE status = 'QUEUED';
