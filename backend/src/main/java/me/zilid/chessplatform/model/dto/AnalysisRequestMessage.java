package me.zilid.chessplatform.model.dto;

import java.time.Instant;
import java.util.UUID;

/** Spring to the analysis service. {@code jobId} is the idempotency key; results are matched on it. */
public record AnalysisRequestMessage(int schemaVersion, UUID jobId, UUID matchId, String pgn, Instant deadline) {
    public static final int SCHEMA_VERSION = 1;
}
