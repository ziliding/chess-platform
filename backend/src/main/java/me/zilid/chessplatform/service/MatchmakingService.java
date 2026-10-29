package me.zilid.chessplatform.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import me.zilid.chessplatform.chess.game.ClockSetting;
import me.zilid.chessplatform.chess.game.Player;
import me.zilid.chessplatform.chess.game.RegisteredPlayer;
import me.zilid.chessplatform.exception.UserNotFoundException;
import me.zilid.chessplatform.model.dto.SeekResponse;
import me.zilid.chessplatform.repository.UserRepo;
import me.zilid.chessplatform.repository.game.MatchmakingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pairs players who want the same clock setting, starting with close ratings and accepting wider gaps the longer a
 * player waits. Clients poll {@link #seek} about once a second; each poll keeps the seek alive.
 */
@Service
public class MatchmakingService {
    private static final Logger logger = LoggerFactory.getLogger(MatchmakingService.class);
    static final int BASE_WINDOW = 100;
    static final int WINDOW_STEP = 50;
    static final Duration WIDEN_EVERY = Duration.ofSeconds(5);

    private final MatchmakingQueue queue;
    private final MatchService matchService;
    private final RatingService ratingService;
    private final UserRepo userRepo;
    private final Clock clock;

    public MatchmakingService(
            MatchmakingQueue queue,
            MatchService matchService,
            RatingService ratingService,
            UserRepo userRepo,
            Clock clock) {
        this.queue = queue;
        this.matchService = matchService;
        this.ratingService = ratingService;
        this.userRepo = userRepo;
        this.clock = clock;
    }

    /** How far apart two ratings may be after waiting {@code waited}. */
    static int ratingWindow(Duration waited) {
        return BASE_WINDOW + WINDOW_STEP * (int) (waited.toMillis() / WIDEN_EVERY.toMillis());
    }

    public SeekResponse seek(Player player, ClockSetting clockSetting) {
        String match = queue.takeMatch(player.id());
        if (match != null) {
            // An empty match means an opponent is creating our game right now
            return match.isEmpty() ? SeekResponse.SEARCHING : SeekResponse.matched(UUID.fromString(match));
        }

        Instant now = clock.instant();
        Instant since = queue.heartbeat(player.id(), clockSetting, now);
        int rating = ratingService.currentRating(player.id(), clockSetting.category());
        // ponytail: only the caller's window counts, so a newcomer can grab someone whose own window is narrower;
        // check both windows in the script if pairings feel lopsided
        int window = ratingWindow(Duration.between(since, now));
        Optional<UUID> opponentId = queue.tryMatch(clockSetting, player.id(), rating, window);
        if (opponentId.isEmpty()) {
            return SeekResponse.SEARCHING;
        }

        UUID gameId;
        try {
            gameId = matchService.createMatchedGame(player, loadPlayer(opponentId.get()), clockSetting);
        } catch (RuntimeException e) {
            // Free the opponent; their next poll seeks again
            queue.clearMatch(opponentId.get());
            throw e;
        }
        queue.offerMatch(opponentId.get(), gameId);
        logger.info("Matched {} with {} in {} game {}", player.id(), opponentId.get(), clockSetting, gameId);
        return SeekResponse.matched(gameId);
    }

    public void cancel(Player player, ClockSetting clockSetting) {
        queue.cancel(player.id(), clockSetting);
    }

    private Player loadPlayer(UUID userId) {
        return userRepo.findById(userId)
                .map(user -> new RegisteredPlayer(user.getId(), user.getUsername()))
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
    }
}
