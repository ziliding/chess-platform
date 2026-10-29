package me.zilid.chessplatform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.List;
import java.util.UUID;
import me.zilid.chessplatform.chess.Color;
import me.zilid.chessplatform.chess.game.ClockSetting;
import me.zilid.chessplatform.chess.game.GameStatus;
import me.zilid.chessplatform.chess.game.TimeControl;
import me.zilid.chessplatform.exception.GameIsOverException;
import me.zilid.chessplatform.exception.GameNotFoundException;
import me.zilid.chessplatform.model.dto.GameCreatedResponse;
import me.zilid.chessplatform.model.dto.GameJoinResponse;
import me.zilid.chessplatform.model.dto.GameStateResponse;
import me.zilid.chessplatform.security.SecurityConfig;
import me.zilid.chessplatform.security.UserPrincipal;
import me.zilid.chessplatform.service.MatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(GameController.class)
@Import(SecurityConfig.class)
class GameControllerWebMvcTest {

    private static final UUID GAME_ID = UUID.fromString("8a169d0a-c121-4d83-a7b3-8ee30f87cfa9");
    private static final UserPrincipal PLAYER = new UserPrincipal(
            UUID.fromString("02410898-174c-4cb5-b8c5-55fe3cc535b9"),
            "player",
            "player@example.com",
            "password",
            true,
            List.of());

    @Autowired
    private MockMvcTester mvcTester;

    @MockitoBean
    private MatchService matchService;

    @Test
    void gameStateRequiresAuthentication() {
        MvcTestResult result =
                mvcTester.get().uri("/api/games/{gameId}/state", GAME_ID).exchange();
        assertThat(result)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Authentication required");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/games/" + GAME_ID + "/state");

        verifyNoInteractions(matchService);
    }

    @Test
    void creatingAndJoiningGamesRequireAuthentication() {
        assertThat(mvcTester.post().uri("/api/games").param("color", "WHITE")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvcTester.post().uri("/api/games/{gameId}/join", GAME_ID)).hasStatus(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(matchService);
    }

    @Test
    void stateEndpointSerializesTheServiceResponse() {
        when(matchService.getGameState(GAME_ID))
                .thenReturn(new GameStateResponse(
                        GameStatus.ONGOING, "starting-fen", "e2", "e4", "BLACK", 299_500, 300_000, true, null));

        MvcTestResult result = mvcTester
                .get()
                .uri("/api/games/{gameId}/state", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.gameStatus").isEqualTo("ONGOING");
        assertThat(result).bodyJson().extractingPath("$.fen").isEqualTo("starting-fen");
        assertThat(result).bodyJson().extractingPath("$.lastMoveFrom").isEqualTo("e2");
        assertThat(result).bodyJson().extractingPath("$.lastMoveTo").isEqualTo("e4");
        assertThat(result).bodyJson().extractingPath("$.turnColor").isEqualTo("BLACK");
        assertThat(result).bodyJson().extractingPath("$.whiteRemainingMillis").isEqualTo(299_500);
        assertThat(result).bodyJson().extractingPath("$.blackRemainingMillis").isEqualTo(300_000);
        assertThat(result).bodyJson().extractingPath("$.clockRunning").isEqualTo(true);

        verify(matchService).getGameState(GAME_ID);
    }

    @Test
    void createGamePassesAuthenticatedPlayerColorAndClockSetting() {
        ClockSetting blitz = ClockSetting.ofMinutes(3, 2);
        when(matchService.createGame(PLAYER.toPlayer(), Color.BLACK, blitz))
                .thenReturn(new GameCreatedResponse(
                        GAME_ID, Color.BLACK, "3+2", TimeControl.BLITZ, "starting-fen", "/game/" + GAME_ID));

        MvcTestResult result = mvcTester
                .post()
                .uri("/api/games")
                .param("color", "BLACK")
                .param("timeControl", "3+2")
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.gameId").isEqualTo(GAME_ID.toString());
        assertThat(result).bodyJson().extractingPath("$.color").isEqualTo("BLACK");
        assertThat(result).bodyJson().extractingPath("$.clockSetting").isEqualTo("3+2");
        assertThat(result).bodyJson().extractingPath("$.timeControl").isEqualTo("BLITZ");
        assertThat(result).bodyJson().extractingPath("$.socketUrl").isEqualTo("/game/" + GAME_ID);

        verify(matchService).createGame(PLAYER.toPlayer(), Color.BLACK, blitz);
    }

    @Test
    void createGameDefaultsToFivePlusThree() {
        ClockSetting fivePlusThree = ClockSetting.ofMinutes(5, 3);
        when(matchService.createGame(PLAYER.toPlayer(), Color.WHITE, fivePlusThree))
                .thenReturn(new GameCreatedResponse(
                        GAME_ID, Color.WHITE, "5+3", TimeControl.BLITZ, "starting-fen", "/game/" + GAME_ID));

        MvcTestResult result = mvcTester
                .post()
                .uri("/api/games")
                .param("color", "WHITE")
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.clockSetting").isEqualTo("5+3");

        verify(matchService).createGame(PLAYER.toPlayer(), Color.WHITE, fivePlusThree);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLITZ", "5", "5+", "+3", "5+3+1", "0+0", "5.1+0", "181+0", "5+-3"})
    void createGameRejectsMalformedClockSettings(String clockSetting) {
        assertThat(mvcTester
                        .post()
                        .uri("/api/games")
                        .param("color", "WHITE")
                        .param("timeControl", clockSetting)
                        .with(user(PLAYER)))
                .hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(matchService);
    }

    @Test
    void joinGameReturnsTheAssignedRole() {
        when(matchService.joinGame(GAME_ID, PLAYER.toPlayer()))
                .thenReturn(new GameJoinResponse(
                        GAME_ID, "WHITE", "10+0", TimeControl.RAPID, "starting-fen", GameStatus.ONGOING, "WHITE"));

        MvcTestResult result = mvcTester
                .post()
                .uri("/api/games/{gameId}/join", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.role").isEqualTo("WHITE");
        assertThat(result).bodyJson().extractingPath("$.currentTurn").isEqualTo("WHITE");

        verify(matchService).joinGame(GAME_ID, PLAYER.toPlayer());
    }

    @Test
    void pgnEndpointReturnsPlainText() {
        when(matchService.getMatchPGN(GAME_ID)).thenReturn("1. e4 e5 2. Nf3");

        assertThat(mvcTester.get().uri("/api/matches/{matchId}/pgn", GAME_ID).with(user(PLAYER)))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .hasBodyTextEqualTo("1. e4 e5 2. Nf3");
    }

    @Test
    void invalidGameRequestUsesTheApplicationErrorFormat() {
        when(matchService.getGameState(GAME_ID)).thenThrow(new IllegalArgumentException("Invalid game"));

        MvcTestResult result = mvcTester
                .get()
                .uri("/api/games/{gameId}/state", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Invalid request");
    }

    @Test
    void missingGameReturnsNotFound() {
        when(matchService.getGameState(GAME_ID)).thenThrow(new GameNotFoundException("Game not found: " + GAME_ID));

        MvcTestResult result = mvcTester
                .get()
                .uri("/api/games/{gameId}/state", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Game not found: " + GAME_ID);
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalDetails() {
        when(matchService.getGameState(GAME_ID)).thenThrow(new RuntimeException("private connection detail"));

        MvcTestResult result = mvcTester
                .get()
                .uri("/api/games/{gameId}/state", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred");
    }

    @Test
    void invalidUuidReturnsBadRequestRatherThanInternalError() {
        MvcTestResult result = mvcTester
                .get()
                .uri("/api/games/not-a-uuid/state")
                .with(user(PLAYER))
                .exchange();
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/games/not-a-uuid/state");
        verifyNoInteractions(matchService);
    }

    @Test
    void missingColorReturnsBadRequest() {
        MvcTestResult result =
                mvcTester.post().uri("/api/games").with(user(PLAYER)).exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
        verifyNoInteractions(matchService);
    }

    @Test
    void invalidColorReturnsBadRequest() {
        MvcTestResult result = mvcTester
                .post()
                .uri("/api/games")
                .param("color", "PURPLE")
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
        verifyNoInteractions(matchService);
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() {
        MvcTestResult result = mvcTester
                .post()
                .uri("/api/games/{gameId}/state", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(405);
        assertThat(result)
                .headers()
                .hasHeaderSatisfying(
                        "Allow",
                        allow -> assertThat(allow)
                                .anySatisfy(value -> assertThat(value).contains("GET")));
        verifyNoInteractions(matchService);
    }

    @Test
    void missingRouteUsesProblemDetails() {
        MvcTestResult result =
                mvcTester.get().uri("/api/nonexistent").with(user(PLAYER)).exchange();
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(404);
    }

    @Test
    void accessDeniedInControllerReturnsForbiddenWithoutInternalDetails() {
        when(matchService.getGameState(GAME_ID)).thenThrow(new AccessDeniedException("private policy"));
        MvcTestResult result = mvcTester
                .get()
                .uri("/api/games/{gameId}/state", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(403);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("You do not have permission to perform this action");
    }

    @Test
    void unexpectedIllegalStateIsAnInternalFailureRatherThanConflict() {
        when(matchService.getGameState(GAME_ID)).thenThrow(new IllegalStateException("private state"));
        MvcTestResult result = mvcTester
                .get()
                .uri("/api/games/{gameId}/state", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(500);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred");
    }

    @Test
    void gameOverIsAnExplicitDomainConflict() {
        when(matchService.joinGame(GAME_ID, PLAYER.toPlayer()))
                .thenThrow(new GameIsOverException("Game is already over"));
        MvcTestResult result = mvcTester
                .post()
                .uri("/api/games/{gameId}/join", GAME_ID)
                .with(user(PLAYER))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Game is already over");
    }
}
