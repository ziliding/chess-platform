package me.zilid.chessplatform.repository;

import static me.zilid.chessplatform.util.EntityFixtures.match;
import static me.zilid.chessplatform.util.EntityFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.uuid.Generators;
import java.time.Instant;
import java.util.UUID;
import me.zilid.chessplatform.model.entity.AnalysisStatus;
import me.zilid.chessplatform.model.entity.GameAnalysis;
import me.zilid.chessplatform.model.entity.MatchRecord;
import me.zilid.chessplatform.model.entity.User;
import me.zilid.chessplatform.util.RepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

@RepositoryTest
class GameAnalysisRepoIT {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String RESULT =
            "{\"engine\":{\"name\":\"Stockfish 17.1\",\"depth\":16},\"moves\":[{\"ply\":1,\"san\":\"e4\"}]}";

    @Autowired
    private GameAnalysisRepo repo;

    @Autowired
    private MatchRecordRepo matchRecordRepo;

    @Autowired
    private TestEntityManager entityManager;

    private User white;
    private User black;
    private MatchRecord match;

    @BeforeEach
    void setUp() {
        white = user(entityManager, "white");
        black = user(entityManager, "black");
        match = match(entityManager, white, black, NOW);
        entityManager.flush();
    }

    private UUID queue(MatchRecord forMatch, User by, Instant deadline) {
        UUID jobId = UUID.randomUUID();
        assertThat(repo.insertQueued(
                        Generators.timeBasedEpochGenerator().generate(), forMatch.getId(), jobId, by.getId(), deadline))
                .isEqualTo(1);
        entityManager.clear();
        return jobId;
    }

    private GameAnalysis reload(UUID jobId) {
        entityManager.clear();
        return repo.findByJobId(jobId).orElseThrow();
    }

    @Test
    void aMatchHasOneAnalysis() {
        UUID jobId = queue(match, white, NOW.plusSeconds(180));

        int again = repo.insertQueued(
                UUID.randomUUID(), match.getId(), UUID.randomUUID(), black.getId(), NOW.plusSeconds(999));

        assertThat(again).isZero();
        GameAnalysis analysis = repo.findByMatchId(match.getId()).orElseThrow();
        assertThat(analysis.getJobId()).isEqualTo(jobId);
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(analysis.getRequestedBy()).isEqualTo(white.getId());
        assertThat(analysis.getDeadline()).isEqualTo(NOW.plusSeconds(180));
        assertThat(analysis.getResult()).isNull();
    }

    @Test
    void aResultIsAppliedOnceAndStoredAsJson() {
        UUID jobId = queue(match, white, NOW.plusSeconds(180));

        assertThat(repo.complete(jobId, RESULT)).isEqualTo(1);
        assertThat(repo.complete(jobId, "{\"engine\":null}")).isZero();
        assertThat(repo.fail(jobId, "TIMED_OUT")).isZero();

        GameAnalysis analysis = reload(jobId);
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(analysis.getCompletedAt()).isNotNull();
        assertThat(JsonMapper.builder().build().readTree(analysis.getResult()))
                .isEqualTo(JsonMapper.builder().build().readTree(RESULT));
    }

    @Test
    void aJobFailsOnceAndALateResultIsIgnored() {
        UUID jobId = queue(match, white, NOW.plusSeconds(180));

        assertThat(repo.fail(jobId, "TIMED_OUT")).isEqualTo(1);
        assertThat(repo.complete(jobId, RESULT)).isZero();

        GameAnalysis analysis = reload(jobId);
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(analysis.getErrorCode()).isEqualTo("TIMED_OUT");
        assertThat(analysis.getResult()).isNull();
    }

    @Test
    void onlyAFailedAnalysisCanBeRequeuedAndItGetsANewJob() {
        UUID first = queue(match, white, NOW.plusSeconds(180));
        UUID second = UUID.randomUUID();
        assertThat(repo.requeueFailed(match.getId(), second, black.getId(), NOW.plusSeconds(500)))
                .isZero();

        repo.fail(first, "ENGINE_ERROR");
        assertThat(repo.requeueFailed(match.getId(), second, black.getId(), NOW.plusSeconds(500)))
                .isEqualTo(1);

        GameAnalysis analysis = reload(second);
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(analysis.getErrorCode()).isNull();
        assertThat(analysis.getCompletedAt()).isNull();
        assertThat(analysis.getRequestedBy()).isEqualTo(black.getId());
        // The earlier attempt's late result can no longer change it
        assertThat(repo.complete(first, RESULT)).isZero();
        assertThat(repo.findByJobId(first)).isEmpty();
    }

    @Test
    void expiredQueuedJobsAreFoundOldestFirst() {
        MatchRecord second = match(entityManager, white, black, NOW.plusSeconds(1));
        MatchRecord third = match(entityManager, white, black, NOW.plusSeconds(2));
        entityManager.flush();
        UUID late = queue(match, white, NOW.minusSeconds(10));
        UUID later = queue(second, white, NOW.minusSeconds(5));
        queue(third, white, NOW.plusSeconds(60));

        assertThat(repo.findExpiredJobIds(NOW, PageRequest.of(0, 10))).containsExactly(late, later);
        assertThat(repo.findExpiredJobIds(NOW, PageRequest.of(0, 1))).containsExactly(late);

        repo.fail(late, "TIMED_OUT");
        assertThat(repo.findExpiredJobIds(NOW, PageRequest.of(0, 10))).containsExactly(later);
    }

    @Test
    void queuedJobsAreCountedPerRequester() {
        MatchRecord second = match(entityManager, white, black, NOW.plusSeconds(1));
        entityManager.flush();
        UUID done = queue(match, white, NOW.plusSeconds(180));
        queue(second, white, NOW.plusSeconds(180));
        repo.complete(done, RESULT);

        assertThat(repo.countByRequestedByAndStatus(white.getId(), AnalysisStatus.QUEUED))
                .isEqualTo(1);
        assertThat(repo.countByRequestedByAndStatus(black.getId(), AnalysisStatus.QUEUED))
                .isZero();
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

        assertThat(repo.findByMatchId(match.getId())).isEmpty();
    }
}
