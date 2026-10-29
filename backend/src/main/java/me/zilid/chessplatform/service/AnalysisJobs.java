package me.zilid.chessplatform.service;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import me.zilid.chessplatform.model.dto.AnalysisPayload;
import me.zilid.chessplatform.model.dto.AnalysisResponse;
import me.zilid.chessplatform.model.entity.AnalysisStatus;
import me.zilid.chessplatform.model.entity.GameAnalysis;
import me.zilid.chessplatform.repository.GameAnalysisRepo;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The state transitions of {@code game_analyses}. Each one is a single conditional statement, so when several instances
 * race (a request, a result, the sweeper) exactly one of them changes the row; it gets the changed analysis back, and
 * the others get nothing.
 */
@Component
public class AnalysisJobs {
    private static final TimeBasedEpochGenerator uuidGenerator = Generators.timeBasedEpochGenerator();

    /** Stored when nobody answered before the deadline. */
    public static final String TIMED_OUT = "TIMED_OUT";

    /** Stored when the request could not be handed to the broker. */
    public static final String NOT_PUBLISHED = "UNAVAILABLE";

    /** Failures that a retry would repeat: the game itself cannot be analyzed. */
    static final Set<String> PERMANENT_FAILURES = Set.of("INVALID_PGN", "TOO_LONG");

    private final GameAnalysisRepo repo;
    private final JsonMapper jsonMapper;

    public AnalysisJobs(GameAnalysisRepo repo, JsonMapper jsonMapper) {
        this.repo = repo;
        this.jsonMapper = jsonMapper;
    }

    public Optional<AnalysisResponse> find(UUID matchId) {
        return repo.findByMatchId(matchId).map(this::toResponse);
    }

    public long countQueued(UUID userId) {
        return repo.countByRequestedByAndStatus(userId, AnalysisStatus.QUEUED);
    }

    /**
     * Queue the first analysis of a match, or a retry of one that failed for a reason a retry can fix. Empty if the
     * match already has an analysis that is queued, done, or failed for good.
     */
    public Optional<AnalysisResponse> queue(UUID matchId, UUID jobId, UUID requestedBy, Instant deadline) {
        int queued = repo.insertQueued(uuidGenerator.generate(), matchId, jobId, requestedBy, deadline);
        if (queued == 0) {
            queued = repo.requeueFailed(matchId, jobId, requestedBy, deadline, PERMANENT_FAILURES);
        }
        return queued == 0 ? Optional.empty() : byJob(jobId);
    }

    /** Store the result of a job that is still queued; empty for a duplicate, stale or late result. */
    public Optional<AnalysisResponse> complete(UUID jobId, AnalysisPayload payload) {
        return repo.complete(jobId, jsonMapper.writeValueAsString(payload)) == 0 ? Optional.empty() : byJob(jobId);
    }

    /** Fail a job that is still queued; empty if a result or an earlier failure got there first. */
    public Optional<AnalysisResponse> fail(UUID jobId, String errorCode) {
        return repo.fail(jobId, errorCode) == 0 ? Optional.empty() : byJob(jobId);
    }

    /** Fail up to {@code limit} jobs past their deadline, oldest first; returns the ones this call failed. */
    public List<AnalysisResponse> failExpired(Instant now, int limit) {
        List<AnalysisResponse> failed = new ArrayList<>();
        for (UUID jobId : repo.findExpiredJobIds(now, PageRequest.of(0, limit))) {
            fail(jobId, TIMED_OUT).ifPresent(failed::add);
        }
        return failed;
    }

    private Optional<AnalysisResponse> byJob(UUID jobId) {
        return repo.findByJobId(jobId).map(this::toResponse);
    }

    private AnalysisResponse toResponse(GameAnalysis analysis) {
        AnalysisPayload payload = parsePayload(analysis.getResult());
        return new AnalysisResponse(
                analysis.getMatchId(),
                analysis.getStatus(),
                analysis.getErrorCode(),
                analysis.getStatus() == AnalysisStatus.FAILED && isRetryable(analysis.getErrorCode()),
                analysis.getDeadline(),
                analysis.getCompletedAt(),
                payload == null ? null : payload.engine(),
                payload == null ? null : payload.initialEval(),
                payload == null ? null : payload.moves(),
                payload == null ? null : payload.summary());
    }

    private static boolean isRetryable(@Nullable String errorCode) {
        return errorCode == null || !PERMANENT_FAILURES.contains(errorCode);
    }

    private @Nullable AnalysisPayload parsePayload(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, AnalysisPayload.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored analysis cannot be read", e);
        }
    }
}
