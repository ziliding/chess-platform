package me.zilid.chessplatform.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import me.zilid.chessplatform.model.entity.AnalysisStatus;
import org.jspecify.annotations.Nullable;

/**
 * An analysis as the client sees it, from {@code GET /api/games/{matchId}/analysis} and pushed to
 * {@code /user/topic/analysis}. The result fields are null until COMPLETED.
 *
 * @param errorCode why a FAILED analysis failed, such as INVALID_PGN or TIMED_OUT
 * @param deadline when a QUEUED analysis is given up on
 */
public record AnalysisResponse(
        UUID matchId,
        AnalysisStatus status,
        @Nullable String errorCode,
        Instant deadline,
        @Nullable Instant completedAt,
        AnalysisPayload.@Nullable Engine engine,
        AnalysisPayload.@Nullable Eval initialEval,
        @Nullable List<AnalysisPayload.Move> moves,
        AnalysisPayload.@Nullable Summary summary) {}
