package me.zilid.chessplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import me.zilid.chessplatform.chess.game.ClockSetting;
import me.zilid.chessplatform.chess.game.RegisteredPlayer;
import me.zilid.chessplatform.chess.game.TimeControl;
import me.zilid.chessplatform.model.dto.SeekResponse;
import me.zilid.chessplatform.model.entity.User;
import me.zilid.chessplatform.repository.UserRepo;
import me.zilid.chessplatform.repository.game.MatchmakingQueue;
import me.zilid.chessplatform.util.MutableClock;
import org.junit.jupiter.api.Test;

class MatchmakingServiceTest {
    private static final ClockSetting CLOCK = ClockSetting.ofMinutes(5, 3);
    private final RegisteredPlayer alice = new RegisteredPlayer(UUID.randomUUID(), "alice");
    private final RegisteredPlayer bob = new RegisteredPlayer(UUID.randomUUID(), "bob");
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final MatchmakingQueue queue = mock(MatchmakingQueue.class);
    private final MatchService matchService = mock(MatchService.class);
    private final RatingService ratingService = mock(RatingService.class);
    private final UserRepo userRepo = mock(UserRepo.class);
    private final MatchmakingService service =
            new MatchmakingService(queue, matchService, ratingService, userRepo, clock);

    @Test
    void windowWidensWhileWaiting() {
        assertThat(MatchmakingService.ratingWindow(Duration.ZERO)).isEqualTo(100);
        assertThat(MatchmakingService.ratingWindow(Duration.ofMillis(4999))).isEqualTo(100);
        assertThat(MatchmakingService.ratingWindow(Duration.ofSeconds(5))).isEqualTo(150);
        assertThat(MatchmakingService.ratingWindow(Duration.ofSeconds(30))).isEqualTo(400);
    }

    @Test
    void searchesWithTheWindowForTimeWaited() {
        when(queue.heartbeat(alice.id(), CLOCK, clock.instant()))
                .thenReturn(clock.instant().minusSeconds(10));
        when(ratingService.currentRating(alice.id(), TimeControl.BLITZ)).thenReturn(1450);
        when(queue.tryMatch(CLOCK, alice.id(), 1450, 200)).thenReturn(Optional.empty());

        assertThat(service.seek(alice, CLOCK)).isEqualTo(SeekResponse.SEARCHING);
    }

    @Test
    void pairingCreatesTheGameAndOffersItToTheOpponent() {
        UUID gameId = UUID.randomUUID();
        when(queue.heartbeat(any(), any(), any())).thenReturn(clock.instant());
        when(ratingService.currentRating(any(), any())).thenReturn(1200);
        when(queue.tryMatch(CLOCK, alice.id(), 1200, 100)).thenReturn(Optional.of(bob.id()));
        User bobUser = user(bob);
        when(userRepo.findById(bob.id())).thenReturn(Optional.of(bobUser));
        when(matchService.createMatchedGame(alice, bob, CLOCK)).thenReturn(gameId);

        assertThat(service.seek(alice, CLOCK)).isEqualTo(SeekResponse.matched(gameId));
        verify(queue).offerMatch(bob.id(), gameId);
    }

    @Test
    void aFailedGameCreationFreesTheOpponent() {
        when(queue.heartbeat(any(), any(), any())).thenReturn(clock.instant());
        when(queue.tryMatch(any(), any(), anyInt(), anyInt())).thenReturn(Optional.of(bob.id()));
        when(userRepo.findById(bob.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.seek(alice, CLOCK)).isInstanceOf(RuntimeException.class);
        verify(queue).clearMatch(bob.id());
        verify(queue, never()).offerMatch(any(), any());
    }

    @Test
    void anOpponentsPairingIsPickedUpOnTheNextPoll() {
        UUID gameId = UUID.randomUUID();
        when(queue.takeMatch(bob.id())).thenReturn("", gameId.toString());

        assertThat(service.seek(bob, CLOCK)).isEqualTo(SeekResponse.SEARCHING);
        assertThat(service.seek(bob, CLOCK)).isEqualTo(SeekResponse.matched(gameId));
        verify(queue, never()).tryMatch(any(), any(), anyInt(), anyInt());
    }

    private static User user(RegisteredPlayer player) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(player.id());
        when(user.getUsername()).thenReturn(player.displayName());
        return user;
    }
}
