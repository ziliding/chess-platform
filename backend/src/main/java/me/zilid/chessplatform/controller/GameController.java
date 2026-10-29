package me.zilid.chessplatform.controller;

import java.util.UUID;
import me.zilid.chessplatform.chess.Color;
import me.zilid.chessplatform.chess.game.ClockSetting;
import me.zilid.chessplatform.model.dto.GameCreatedResponse;
import me.zilid.chessplatform.model.dto.GameJoinResponse;
import me.zilid.chessplatform.model.dto.GameStateResponse;
import me.zilid.chessplatform.model.dto.MatchRecordResponse;
import me.zilid.chessplatform.security.UserPrincipal;
import me.zilid.chessplatform.service.MatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class GameController {
    private static final Logger logger = LoggerFactory.getLogger(GameController.class);

    private final MatchService matchService;

    public GameController(MatchService matchService) {
        this.matchService = matchService;
    }

    @GetMapping("/games/users/{userId}")
    public Page<MatchRecordResponse> getGames(@PathVariable("userId") UUID userId, Pageable pageable) {
        logger.debug("Fetching games for user: {}", userId);
        return matchService.findMatches(userId, pageable);
    }

    @GetMapping("/matches/{matchId}/pgn")
    public String getMatchPGN(@PathVariable("matchId") UUID matchId) {
        logger.debug("Fetching PGN for match: {}", matchId);
        return matchService.getMatchPGN(matchId);
    }

    @PostMapping("/games")
    @ResponseStatus(HttpStatus.CREATED)
    public GameCreatedResponse createGame(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam("color") Color color,
            @RequestParam(value = "timeControl", defaultValue = "5+3") String timeControl) {
        logger.info("User {} creating {} game with color {}", currentUser.getUsername(), timeControl, color);
        return matchService.createGame(currentUser.toPlayer(), color, ClockSetting.parse(timeControl));
    }

    @PostMapping("/games/{gameId}/join")
    public GameJoinResponse joinGame(@PathVariable UUID gameId, @AuthenticationPrincipal UserPrincipal currentUser) {
        logger.info("User {} joining game {}", currentUser.getUsername(), gameId);
        return matchService.joinGame(gameId, currentUser.toPlayer());
    }

    @GetMapping("/games/{gameId}/state")
    public GameStateResponse getGameState(@PathVariable UUID gameId) {
        logger.debug("Fetching game state for game: {}", gameId);
        return matchService.getGameState(gameId);
    }
}
