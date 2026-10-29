package me.zilid.chessplatform.service;

import static me.zilid.chessplatform.util.EntityFixtures.match;
import static me.zilid.chessplatform.util.EntityFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import me.zilid.chessplatform.model.dto.AnalysisPayload;
import me.zilid.chessplatform.model.dto.AnalysisResponse;
import me.zilid.chessplatform.model.entity.AnalysisStatus;
import me.zilid.chessplatform.model.entity.MatchRecord;
import me.zilid.chessplatform.model.entity.User;
import me.zilid.chessplatform.repository.GameAnalysisRepo;
import me.zilid.chessplatform.repository.MatchParticipants;
import me.zilid.chessplatform.repository.MatchRecordRepo;
import me.zilid.chessplatform.util.RepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import tools.jackson.databind.json.JsonMapper;

/** The transitions of {@code game_analyses}, against real PostgreSQL. */
@RepositoryTest
class AnalysisJobsIT {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final AnalysisPayload PAYLOAD = new AnalysisPayload(
            new AnalysisPayload.Engine("Stockfish 17.1", 16),
            new AnalysisPayload.Eval(20, null),
            List.of(new AnalysisPayload.Move(
                    1, "e4", "e2e4", new AnalysisPayload.Eval(30, null), "e4", "e2e4", 0.0, "BEST", null)),
            new AnalysisPayload.Summary("White played well.", "TEMPLATE"));

    @Autowired
    private GameAnalysisRepo repo;

    @Autowired
    private MatchRecordRepo matchRecordRepo;

    @Autowired
    private TestEntityManager entityManager;

    private AnalysisJobs jobs;
    private User white;
    private User black;
    private MatchRecord match;

    @BeforeEach
    void setUp() {
        jobs = new AnalysisJobs(repo, JsonMapper.builder().build());
        white = user(entityManager, "white");
        black = user(entityManager, "black");
        match = match(entityManager, white, black, NOW);
        entityManager.flush();
    }

    private UUID queue(MatchRecord forMatch, User by, Instant deadline) {
        UUID jobId = UUID.randomUUID();
        assertThat(jobs.queue(forMatch.getId(), jobId, by.getId(), deadline)).isPresent();
        return jobId;
    }

    private AnalysisResponse stored() {
        entityManager.clear();
        return jobs.find(match.getId()).orElseThrow();
    }

    @Test
    void aMatchHasOneAnalysis() {
        AnalysisResponse queued = jobs.queue(match.getId(), UUID.randomUUID(), white.getId(), NOW.plusSeconds(180))
                .orElseThrow();

        assertThat(jobs.queue(match.getId(), UUID.randomUUID(), black.getId(), NOW.plusSeconds(999)))
                .isEmpty();

        assertThat(queued.status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(queued.retryable()).isFalse();
        assertThat(stored()).isEqualTo(queued);
        assertThat(queued.deadline()).isEqualTo(NOW.plusSeconds(180));
        assertThat(jobs.countQueued(black.getId())).isZero();
    }

    @Test
    void aResultIsAppliedOnceAndReadBack() {
        UUID jobId = queue(match, white, NOW.plusSeconds(180));

        AnalysisResponse completed = jobs.complete(jobId, PAYLOAD).orElseThrow();
        assertThat(jobs.complete(jobId, PAYLOAD)).isEmpty();
        assertThat(jobs.fail(jobId, "TIMED_OUT")).isEmpty();

        assertThat(completed.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.moves()).isEqualTo(PAYLOAD.moves());
        assertThat(completed.summary()).isEqualTo(PAYLOAD.summary());
        assertThat(stored()).isEqualTo(completed);
    }

    @Test
    void aJobFailsOnceAndALateResultIsIgnored() {
        UUID jobId = queue(match, white, NOW.plusSeconds(180));

        AnalysisResponse failed = jobs.fail(jobId, "TIMED_OUT").orElseThrow();
        assertThat(jobs.complete(jobId, PAYLOAD)).isEmpty();

        assertThat(failed.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("TIMED_OUT");
        assertThat(failed.retryable()).isTrue();
        assertThat(stored().moves()).isNull();
    }

    @Test
    void aRetryableFailureIsRequeuedUnderANewJob() {
        UUID first = queue(match, white, NOW.plusSeconds(180));
        UUID second = UUID.randomUUID();
        assertThat(jobs.queue(match.getId(), second, black.getId(), NOW.plusSeconds(500)))
                .isEmpty();

        jobs.fail(first, "ENGINE_ERROR");
        AnalysisResponse requeued = jobs.queue(match.getId(), second, black.getId(), NOW.plusSeconds(500))
                .orElseThrow();

        assertThat(requeued.status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(requeued.errorCode()).isNull();
        assertThat(requeued.completedAt()).isNull();
        assertThat(requeued.deadline()).isEqualTo(NOW.plusSeconds(500));
        assertThat(jobs.countQueued(black.getId())).isEqualTo(1);
        // The earlier attempt's late result can no longer change it
        assertThat(jobs.complete(first, PAYLOAD)).isEmpty();
        assertThat(jobs.complete(second, PAYLOAD)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_PGN", "TOO_LONG"})
    void aPermanentFailureIsNeverRequeued(String errorCode) {
        UUID jobId = queue(match, white, NOW.plusSeconds(180));

        assertThat(jobs.fail(jobId, errorCode).orElseThrow().retryable()).isFalse();

        assertThat(jobs.queue(match.getId(), UUID.randomUUID(), white.getId(), NOW.plusSeconds(500)))
                .isEmpty();
        assertThat(stored().status()).isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    void expiredJobsAreFailedOldestFirstAndOnlyOnce() {
        MatchRecord second = match(entityManager, white, black, NOW.plusSeconds(1));
        MatchRecord third = match(entityManager, white, black, NOW.plusSeconds(2));
        entityManager.flush();
        queue(match, white, NOW.minusSeconds(10));
        queue(second, white, NOW.minusSeconds(5));
        queue(third, white, NOW.plusSeconds(60));

        assertThat(jobs.failExpired(NOW, 1))
                .extracting(AnalysisResponse::matchId)
                .containsExactly(match.getId());
        List<AnalysisResponse> rest = jobs.failExpired(NOW, 10);
        assertThat(jobs.failExpired(NOW, 10)).isEmpty();

        assertThat(rest).extracting(AnalysisResponse::matchId).containsExactly(second.getId());
        assertThat(rest.getFirst().errorCode()).isEqualTo(AnalysisJobs.TIMED_OUT);
        assertThat(jobs.countQueued(white.getId())).isEqualTo(1);
    }

    @Test
    void queuedJobsAreCountedPerRequester() {
        MatchRecord second = match(entityManager, white, black, NOW.plusSeconds(1));
        entityManager.flush();
        UUID done = queue(match, white, NOW.plusSeconds(180));
        queue(second, white, NOW.plusSeconds(180));
        jobs.complete(done, PAYLOAD);

        assertThat(jobs.countQueued(white.getId())).isEqualTo(1);
        assertThat(jobs.countQueued(black.getId())).isZero();
    }

    // COMPLETED needs a result and FAILED needs an error code. One statement per test: an error aborts the transaction.
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED"})
    void theDatabaseRefusesAStatusWithoutItsData(String status) {
        UUID jobId = queue(match, white, NOW.plusSeconds(180));

        assertThatThrownBy(() -> entityManager
                        .getEntityManager()
                        .createNativeQuery("UPDATE game_analyses SET status = '" + status + "' WHERE job_id = :id")
                        .setParameter("id", jobId)
                        .executeUpdate())
                .hasRootCauseInstanceOf(PSQLException.class)
                .hasMessageContaining("game_analyses_check");
    }

    @Test
    void participantsExposeBothPlayersAndThePgn() {
        MatchParticipants participants =
                matchRecordRepo.findParticipantsById(match.getId()).orElseThrow();

        assertThat(participants.whiteId()).isEqualTo(white.getId());
        assertThat(participants.blackName()).isEqualTo(black.getUsername());
        assertThat(participants.pgn()).isEqualTo("1. e4 e5");
        assertThat(participants.isPlayer(black.getId())).isTrue();
        assertThat(participants.isPlayer(UUID.randomUUID())).isFalse();
        assertThat(matchRecordRepo.findParticipantsById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void deletingTheMatchDeletesItsAnalysis() {
        queue(match, white, NOW.plusSeconds(180));

        entityManager
                .getEntityManager()
                .createNativeQuery("DELETE FROM match_records WHERE id = :id")
                .setParameter("id", match.getId())
                .executeUpdate();

        assertThat(jobs.find(match.getId())).isEmpty();
    }
}
