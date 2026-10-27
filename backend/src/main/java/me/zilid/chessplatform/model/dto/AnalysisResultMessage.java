package me.zilid.chessplatform.model.dto;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The analysis service to Spring. COMPLETED carries the four result fields; FAILED carries {@code error}.
 *
 * @param status COMPLETED or FAILED
 */
public record AnalysisResultMessage(
        int schemaVersion,
        UUID jobId,
        UUID matchId,
        String status,
        AnalysisPayload.@Nullable Engine engine,
        AnalysisPayload.@Nullable Eval initialEval,
        @Nullable List<AnalysisPayload.Move> moves,
        AnalysisPayload.@Nullable Summary summary,
        @Nullable ErrorInfo error) {
    public record ErrorInfo(String code, String message) {}
}
