package me.zilid.chessplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The AI analysis of an archived match. Rows are created and moved between states by the conditional statements in
 * {@code GameAnalysisRepo}, never by changing and saving the entity, so concurrent instances cannot overwrite each
 * other.
 */
@Entity
@Table(name = "game_analyses")
public class GameAnalysis extends BaseEntity {
    @Column(name = "match_id", nullable = false, updatable = false)
    private UUID matchId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "requested_by")
    private @Nullable UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AnalysisStatus status;

    @Column(name = "deadline", nullable = false)
    private Instant deadline;

    /** JSON of {@code AnalysisPayload}; null unless COMPLETED. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result")
    private @Nullable String result;

    @Column(name = "error_code")
    private @Nullable String errorCode;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    protected GameAnalysis() {}

    public UUID getMatchId() {
        return matchId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public @Nullable UUID getRequestedBy() {
        return requestedBy;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public @Nullable String getResult() {
        return result;
    }

    public @Nullable String getErrorCode() {
        return errorCode;
    }

    public @Nullable Instant getCompletedAt() {
        return completedAt;
    }
}
