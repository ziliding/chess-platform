package me.zilid.chessplatform.repository;

import static me.zilid.chessplatform.util.EntityFixtures.match;
import static me.zilid.chessplatform.util.EntityFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import me.zilid.chessplatform.chess.game.GameStatus;
import me.zilid.chessplatform.chess.game.TimeControl;
import me.zilid.chessplatform.model.entity.MatchRecord;
import me.zilid.chessplatform.model.entity.User;
import me.zilid.chessplatform.rating.RatingChange;
import me.zilid.chessplatform.util.RepositoryTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@RepositoryTest
class MatchRecordRepoIT {

    @Autowired
    private MatchRecordRepo matchRecordRepo;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void historyIncludesEitherColorAndPaginatesByLatestEndTime() {
        User alice = user(entityManager, "alice");
        User bob = user(entityManager, "bob");
        User carol = user(entityManager, "carol");
        User dave = user(entityManager, "dave");
        MatchRecord oldest = match(entityManager, alice, bob, Instant.parse("2026-01-01T12:00:00Z"));
        MatchRecord newest = match(entityManager, bob, alice, Instant.parse("2026-01-03T12:00:00Z"));
        MatchRecord middle = match(entityManager, alice, carol, Instant.parse("2026-01-02T12:00:00Z"));
        match(entityManager, carol, dave, Instant.parse("2026-01-04T12:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        PageRequest firstTwo = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "endTime"));
        Page<MatchRecord> firstPage =
                matchRecordRepo.findByWhitePlayer_IdOrBlackPlayer_Id(alice.getId(), alice.getId(), firstTwo);
        Page<MatchRecord> secondPage =
                matchRecordRepo.findByWhitePlayer_IdOrBlackPlayer_Id(alice.getId(), alice.getId(), firstTwo.next());

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.map(MatchRecord::getId).getContent()).containsExactly(newest.getId(), middle.getId());
        assertThat(secondPage.map(MatchRecord::getId).getContent()).containsExactly(oldest.getId());
    }

    // MatchService archives a game's status symbol and reason, so each finished status must pass the column checks
    @ParameterizedTest
    @EnumSource(
            value = GameStatus.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"ONGOING", "ABORTED"})
    void everyArchivedGameStatusFitsTheSchema(GameStatus status) {
        MatchRecord reloaded = entityManager.persistFlushFind(new MatchRecord(
                user(entityManager, "white"),
                user(entityManager, "black"),
                status.getSymbol(),
                status.getReason(),
                "1. e4 e5",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:10:00Z")));

        assertThat(reloaded.getMatchResult()).isEqualTo(status.getSymbol());
        assertThat(reloaded.getReason()).isEqualTo(status.getReason());
    }

    @Test
    void archivedMatchRoundTripsWithItsRatingSnapshot() {
        User white = user(entityManager, "white");
        User black = user(entityManager, "black");
        MatchRecord match = new MatchRecord(
                white,
                black,
                "1-0",
                "CHECKMATE",
                "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:30:00Z"));
        match.setTimeControl(TimeControl.RAPID);
        match.setRatingChange(new RatingChange(white.getId(), black.getId(), 1220, 1180, 20, -20));

        MatchRecord reloaded = entityManager.persistFlushFind(match);

        assertThat(reloaded.getWhitePlayer()).extracting(User::getId).isEqualTo(white.getId());
        assertThat(reloaded.getBlackPlayer()).extracting(User::getId).isEqualTo(black.getId());
        assertThat(reloaded.getPgn()).isEqualTo("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#");
        assertThat(reloaded.getStartTime()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(reloaded.getEndTime()).isEqualTo(Instant.parse("2026-01-01T00:30:00Z"));
        assertThat(reloaded.getTimeControl()).isEqualTo(TimeControl.RAPID);
        assertThat(reloaded.getWhiteRating()).isEqualTo(1220);
        assertThat(reloaded.getBlackRating()).isEqualTo(1180);
        assertThat(reloaded.getWhiteRatingChange()).isEqualTo(20);
        assertThat(reloaded.getBlackRatingChange()).isEqualTo(-20);
    }
}
