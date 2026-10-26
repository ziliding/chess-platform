package me.zilid.chessplatform.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.zilid.chessplatform.model.entity.AnalysisStatus;
import me.zilid.chessplatform.model.entity.GameAnalysis;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every state change is a conditional statement that returns the number of rows it changed, so when several instances
 * race (a request, a result, the sweeper) exactly one of them sees 1 and the rest see 0.
 */
@Repository
public interface GameAnalysisRepo extends CrudRepository<GameAnalysis, UUID> {
    Optional<GameAnalysis> findByMatchId(UUID matchId);

    Optional<GameAnalysis> findByJobId(UUID jobId);

    long countByRequestedByAndStatus(UUID requestedBy, AnalysisStatus status);

    @Query("select a.jobId from GameAnalysis a where a.status = 'QUEUED' and a.deadline < :now order by a.deadline")
    List<UUID> findExpiredJobIds(@Param("now") Instant now, Pageable limit);

    /** Queue the first analysis of a match; 0 if another request got there first. */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
                    INSERT INTO game_analyses (id, match_id, job_id, requested_by, status, deadline)
                    VALUES (:id, :matchId, :jobId, :requestedBy, 'QUEUED', :deadline)
                    ON CONFLICT (match_id) DO NOTHING
                    """)
    int insertQueued(
            @Param("id") UUID id,
            @Param("matchId") UUID matchId,
            @Param("jobId") UUID jobId,
            @Param("requestedBy") UUID requestedBy,
            @Param("deadline") Instant deadline);

    /** Queue a retry of a failed analysis under a new job; 0 if it is no longer FAILED. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
                    UPDATE game_analyses
                    SET status = 'QUEUED', job_id = :jobId, requested_by = :requestedBy, deadline = :deadline,
                        result = NULL, error_code = NULL, completed_at = NULL,
                        updated_at = now(), version = version + 1
                    WHERE match_id = :matchId AND status = 'FAILED'
                    """)
    int requeueFailed(
            @Param("matchId") UUID matchId,
            @Param("jobId") UUID jobId,
            @Param("requestedBy") UUID requestedBy,
            @Param("deadline") Instant deadline);

    /** Store the result of a job that is still QUEUED; 0 for a duplicate, stale or late result. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
                    UPDATE game_analyses
                    SET status = 'COMPLETED', result = CAST(:result AS jsonb), completed_at = now(),
                        updated_at = now(), version = version + 1
                    WHERE job_id = :jobId AND status = 'QUEUED'
                    """)
    int complete(@Param("jobId") UUID jobId, @Param("result") String result);

    /** Fail a job that is still QUEUED; 0 if a result or an earlier failure got there first. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
                    UPDATE game_analyses
                    SET status = 'FAILED', error_code = :errorCode, completed_at = now(),
                        updated_at = now(), version = version + 1
                    WHERE job_id = :jobId AND status = 'QUEUED'
                    """)
    int fail(@Param("jobId") UUID jobId, @Param("errorCode") String errorCode);
}
