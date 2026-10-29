package me.zilid.chessplatform.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import me.zilid.chessplatform.chess.Color;
import me.zilid.chessplatform.chess.PieceType;
import me.zilid.chessplatform.chess.game.ClockSetting;
import me.zilid.chessplatform.chess.game.Game;
import me.zilid.chessplatform.chess.game.GameStatus;
import me.zilid.chessplatform.chess.game.Player;
import me.zilid.chessplatform.exception.GameIsOverException;
import me.zilid.chessplatform.exception.GameNotFoundException;
import me.zilid.chessplatform.model.converter.MatchRecordConverter;
import me.zilid.chessplatform.model.dto.GameCreatedResponse;
import me.zilid.chessplatform.model.dto.GameJoinResponse;
import me.zilid.chessplatform.model.dto.GameStateResponse;
import me.zilid.chessplatform.model.dto.MatchRecordResponse;
import me.zilid.chessplatform.model.entity.MatchRecord;
import me.zilid.chessplatform.model.entity.User;
import me.zilid.chessplatform.rating.RatingChange;
import me.zilid.chessplatform.repository.MatchRecordRepo;
import me.zilid.chessplatform.repository.UserRepo;
import me.zilid.chessplatform.repository.game.GameStateStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchService {

    private static final Logger logger = LoggerFactory.getLogger(MatchService.class);
    private static final Duration FINISHED_GAME_TTL = Duration.ofMinutes(1);

    private final MatchRecordConverter matchRecordConverter;
    private final MatchRecordRepo matchRecordRepo;
    private final UserRepo userRepo;
    private final RatingService ratingService;
    private final GameStateStore gameStateStore;
    private final Clock clock;

    public MatchService(
            MatchRecordRepo matchRecordRepo,
            MatchRecordConverter matchRecordConverter,
            UserRepo userRepo,
            RatingService ratingService,
            GameStateStore gameStateStore,
            Clock clock) {
        this.matchRecordRepo = matchRecordRepo;
        this.matchRecordConverter = matchRecordConverter;
        this.userRepo = userRepo;
        this.ratingService = ratingService;
        this.gameStateStore = gameStateStore;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<MatchRecordResponse> findMatches(UUID userId, Pageable pageable) {
        logger.debug("Finding matches for user: {}", userId);
        pageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "endTime"));

        Page<MatchRecord> games = matchRecordRepo.findByWhitePlayer_IdOrBlackPlayer_Id(userId, userId, pageable);
        logger.debug("Found {} matches for user {}", games.getTotalElements(), userId);
        return games.map(matchRecordConverter::toResponse);
    }

    @Transactional(readOnly = true)
    public String getMatchPGN(UUID matchId) {
        logger.debug("Fetching PGN for match: {}", matchId);
        MatchRecord matchRecord = matchRecordRepo
                .findById(matchId)
                .orElseThrow(() -> new GameNotFoundException("Game with id " + matchId + " does not exist"));
        return matchRecord.getPgn();
    }

    public GameCreatedResponse createGame(Player currentUser, Color color, ClockSetting clockSetting) {
        UUID gameId = UUID.randomUUID();
        logger.info(
                "Creating new {} game {} for user {} with color {}",
                clockSetting,
                gameId,
                currentUser.displayName(),
                color);
        Game game = color.isWhite()
                ? new Game(currentUser, null, clockSetting, clock.instant())
                : new Game(null, currentUser, clockSetting, clock.instant());
        saveGame(gameId, game);

        return new GameCreatedResponse(
                gameId, color, clockSetting.toString(), clockSetting.category(), game.getFen(), "/game/" + gameId);
    }

    /**
     * Start a game between two paired players, colours chosen at random. Both seats are filled, so the first-move timer
     * runs from now and the game is aborted if either player never shows up.
     */
    public UUID createMatchedGame(Player first, Player second, ClockSetting clockSetting) {
        UUID gameId = UUID.randomUUID();
        boolean firstIsWhite = ThreadLocalRandom.current().nextBoolean();
        Player white = firstIsWhite ? first : second;
        Player black = firstIsWhite ? second : first;
        logger.info(
                "Creating matched {} game {}: {} vs {}",
                clockSetting,
                gameId,
                white.displayName(),
                black.displayName());
        saveGame(gameId, new Game(white, black, clockSetting, clock.instant()));
        return gameId;
    }

    public GameJoinResponse joinGame(UUID gameId, Player currentUser) {
        logger.info("User {} attempting to join game {}", currentUser.displayName(), gameId);
        return updateGame(gameId, game -> {
            String role;
            if (currentUser.equals(game.getWhitePlayer())) {
                role = "WHITE"; // reconnect
                logger.debug("User {} reconnecting as WHITE to game {}", currentUser.displayName(), gameId);
            } else if (currentUser.equals(game.getBlackPlayer())) {
                role = "BLACK"; // reconnect
                logger.debug("User {} reconnecting as BLACK to game {}", currentUser.displayName(), gameId);
            } else if (game.getWhitePlayer() == null) {
                game.seat(Color.WHITE, currentUser, clock.instant());
                role = "WHITE";
                logger.info("User {} joined game {} as WHITE", currentUser.displayName(), gameId);
            } else if (game.getBlackPlayer() == null) {
                game.seat(Color.BLACK, currentUser, clock.instant());
                role = "BLACK";
                logger.info("User {} joined game {} as BLACK", currentUser.displayName(), gameId);
            } else {
                role = "SPECTATOR"; // spectator
                logger.info("User {} joined game {} as SPECTATOR", currentUser.displayName(), gameId);
            }

            return new GameJoinResponse(
                    gameId,
                    role,
                    game.getClockSetting().toString(),
                    game.getTimeControl(),
                    game.getFen(),
                    game.getStatus(),
                    game.getTurnColor().name());
        });
    }

    public GameStateResponse makeMove(
            Player currentUser, UUID gameId, String moveFrom, String moveTo, @Nullable PieceType promotion) {
        return updateGame(gameId, game -> {
            Instant now = clock.instant();
            requirePlayer(game, currentUser);
            if (game.isGameOver()) {
                throw new IllegalStateException("Game is already over");
            }
            if (game.checkTimeout(now)) {
                logger.info("Game {} ended on time before the move {} to {}", gameId, moveFrom, moveTo);
                return buildGameStateResponse(game);
            }
            if (!game.isUserTurn(currentUser)) {
                throw new IllegalStateException("It is not your turn");
            }
            if (!game.makeMove(moveFrom, moveTo, promotion, now)) {
                throw new IllegalArgumentException("Invalid move: " + moveFrom + " to " + moveTo);
            }
            logger.info("Move executed in game {}: {} to {}", gameId, moveFrom, moveTo);
            return buildGameStateResponse(game);
        });
    }

    public void offerDraw(Player currentUser, UUID gameId) {
        logger.info("User {} offering draw in game {}", currentUser.displayName(), gameId);
        updateGame(gameId, game -> {
            Color color = playerColor(game, currentUser);
            if (game.isGameOver() || game.hasTimedOut(clock.instant())) {
                throw new IllegalStateException("Game is over");
            }
            game.offerDraw(color);
            logger.info("Draw offered by {} in game {}", color, gameId);
            return color;
        });
    }

    public GameStateResponse acceptDraw(Player currentUser, UUID gameId) {
        return updateGame(gameId, game -> {
            Color color = playerColor(game, currentUser);
            if (game.isGameOver()) {
                logger.warn("Attempted draw acceptance on completed game {}", gameId);
                throw new GameIsOverException("Game is already over");
            }
            Instant now = clock.instant();
            if (game.checkTimeout(now)) {
                return buildGameStateResponse(game);
            }
            game.acceptDraw(color, now);
            return buildGameStateResponse(game);
        });
    }

    public GameStateResponse resign(Player currentUser, UUID gameId) {
        return updateGame(gameId, game -> {
            Color color = playerColor(game, currentUser);
            if (game.isGameOver()) {
                logger.warn("Attempted resignation on completed game {}", gameId);
                throw new GameIsOverException("Game is already over");
            }
            Instant now = clock.instant();
            if (game.checkTimeout(now)) {
                return buildGameStateResponse(game);
            }
            game.resign(color, now);
            logger.info("Player {} resigned in game {}", color, gameId);
            return buildGameStateResponse(game);
        });
    }

    /**
     * End the game if its time limit has passed, as one load-check-store step under the game's lock. Safe to call from
     * any number of instances at once: only the call that actually ends the game gets a state back, so only that caller
     * should publish the result and archive the match.
     *
     * @return the final state if this call ended the game; empty if the game is missing, already over, or still within
     *     its time limit
     */
    public Optional<GameStateResponse> checkTimeout(UUID gameId) {
        return gameStateStore.withLock(gameId, () -> {
            Game game = getGameSession(gameId);
            if (game == null || !game.checkTimeout(clock.instant())) {
                return Optional.empty();
            }
            saveGame(gameId, game);
            logger.info("Game {} ended on time: {}", gameId, game.getStatus());
            return Optional.of(buildGameStateResponse(game));
        });
    }

    public GameStateResponse getGameState(UUID gameId) {
        Game game = getGameOrThrow(gameId);

        return buildGameStateResponse(game);
    }

    /** Build a GameStateResponse from the current game state */
    public GameStateResponse buildGameStateResponse(Game game) {
        Instant now = clock.instant();
        synchronized (game) {
            return new GameStateResponse(
                    game.getStatus(),
                    game.getFen(),
                    game.getLastMoveFrom(),
                    game.getLastMoveTo(),
                    game.getTurnColor().name(),
                    game.getRemaining(Color.WHITE, now).toMillis(),
                    game.getRemaining(Color.BLACK, now).toMillis(),
                    game.isClockRunning(),
                    firstMoveRemainingMillis(game, now));
        }
    }

    private static @Nullable Long firstMoveRemainingMillis(Game game, Instant now) {
        Instant deadline = game.getFirstMoveDeadline();
        if (deadline == null || game.isGameOver()) {
            return null;
        }
        Duration left = Duration.between(now, deadline);
        return left.isNegative() ? 0L : left.toMillis();
    }

    /** Save the finished game and apply its result to both players' ratings in one transaction. */
    @Transactional
    public RatingChange archiveMatch(UUID matchId, Game game) {
        if (!game.isGameOver()) {
            throw new IllegalStateException("Game is not over");
        }
        if (game.getStatus() == GameStatus.ABORTED) {
            throw new IllegalStateException("Aborted games are not archived");
        }
        Player white = game.getWhitePlayer();
        Player black = game.getBlackPlayer();
        Instant endTime = game.getEndTime();
        // A lone player can resign before an opponent joins; there is no match to record then
        if (white == null || black == null || endTime == null) {
            throw new IllegalStateException("Game " + matchId + " ended without both players seated");
        }
        logger.info(
                "Archiving match {} with result: {}", matchId, game.getStatus().getSymbol());
        UUID whitePlayerId = white.id();
        UUID blackPlayerId = black.id();
        User whitePlayer = userRepo.getReferenceById(whitePlayerId);
        User blackPlayer = userRepo.getReferenceById(blackPlayerId);
        MatchRecord matchRecord = new MatchRecord(
                whitePlayer,
                blackPlayer,
                game.getStatus().getSymbol(),
                game.getStatus().getReason(),
                game.getNotation(),
                game.getStartTime(),
                endTime);
        matchRecord.setTimeControl(game.getTimeControl());
        RatingChange ratingChange =
                ratingService.applyResult(whitePlayerId, blackPlayerId, game.getTimeControl(), game.getStatus());
        matchRecord.setRatingChange(ratingChange);
        matchRecordRepo.save(matchRecord);
        logger.info("Match {} archived successfully", matchId);
        return ratingChange;
    }

    /** Keep a finished game readable for a short while (late joiners, reconnects), then let Redis evict it */
    public void scheduleGameCleanup(UUID gameId) {
        gameStateStore.expireGame(gameId, FINISHED_GAME_TTL);
        logger.info("Game {} will be removed in {}", gameId, FINISHED_GAME_TTL);
    }

    /** Load a game session (useful for testing or administrative purposes) */
    public @Nullable Game getGameSession(UUID gameId) {
        return gameStateStore.loadGame(gameId);
    }

    public Game getGameOrThrow(UUID gameId) {
        Game game = getGameSession(gameId);
        if (game == null) {
            throw new GameNotFoundException("Game not found: " + gameId);
        }
        return game;
    }

    /**
     * Load the game, apply {@code action} and store the result, all under the game's distributed lock. Nothing is
     * stored if {@code action} throws.
     */
    private <T> T updateGame(UUID gameId, Function<Game, T> action) {
        return gameStateStore.withLock(gameId, () -> {
            Game game = getGameOrThrow(gameId);
            T result = action.apply(game);
            saveGame(gameId, game);
            return result;
        });
    }

    private void saveGame(UUID gameId, Game game) {
        gameStateStore.storeGame(gameId, game);
    }

    public void requirePlayer(Game game, Player user) {
        if (!game.isValidPlayer(user)) {
            throw new IllegalStateException("You are not a player in this game");
        }
    }

    private Color playerColor(Game game, Player user) {
        requirePlayer(game, user);
        Color color = game.getPlayerColor(user);
        if (color == null) {
            throw new IllegalStateException("You are not a player in this game");
        }
        return color;
    }
}
