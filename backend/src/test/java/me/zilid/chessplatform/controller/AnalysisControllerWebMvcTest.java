package me.zilid.chessplatform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import me.zilid.chessplatform.exception.AnalysisUnavailableException;
import me.zilid.chessplatform.exception.GameNotFoundException;
import me.zilid.chessplatform.exception.TooManyAnalysesException;
import me.zilid.chessplatform.model.dto.AnalysisPayload;
import me.zilid.chessplatform.model.dto.AnalysisResponse;
import me.zilid.chessplatform.model.entity.AnalysisStatus;
import me.zilid.chessplatform.security.SecurityConfig;
import me.zilid.chessplatform.security.UserPrincipal;
import me.zilid.chessplatform.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(AnalysisController.class)
@Import(SecurityConfig.class)
class AnalysisControllerWebMvcTest {
    private static final UUID MATCH_ID = UUID.fromString("0192c7a1-0e44-7d2b-8a10-6f3e2b9c4d55");
    private static final UserPrincipal PLAYER = new UserPrincipal(
            UUID.fromString("02410898-174c-4cb5-b8c5-55fe3cc535b9"),
            "player",
            "player@example.com",
            "password",
            true,
            List.of());
    private static final Instant DEADLINE = Instant.parse("2026-10-26T12:03:00Z");

    @Autowired
    private MockMvcTester mvcTester;

    @MockitoBean
    private AnalysisService analysisService;

    private static AnalysisResponse queued() {
        return new AnalysisResponse(MATCH_ID, AnalysisStatus.QUEUED, null, DEADLINE, null, null, null, null, null);
    }

    private static AnalysisResponse completed() {
        return new AnalysisResponse(
                MATCH_ID,
                AnalysisStatus.COMPLETED,
                null,
                DEADLINE,
                Instant.parse("2026-10-26T12:00:12Z"),
                new AnalysisPayload.Engine("Stockfish 17.1", 16),
                new AnalysisPayload.Eval(20, null),
                List.of(new AnalysisPayload.Move(
                        6, "Nf6", "g8f6", new AnalysisPayload.Eval(null, 1), "g6", "g7g6", 1.06, "BLUNDER")),
                new AnalysisPayload.Summary("Black blundered.", "TEMPLATE"));
    }

    @Test
    void bothEndpointsRequireAuthentication() {
        assertThat(mvcTester.get().uri("/api/matches/{id}/analysis", MATCH_ID)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvcTester.post().uri("/api/matches/{id}/analysis", MATCH_ID)).hasStatus(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(analysisService);
    }

    @Test
    void getSerializesAFinishedAnalysis() {
        when(analysisService.get(MATCH_ID)).thenReturn(completed());

        MvcTestResult result = mvcTester
                .get()
                .uri("/api/matches/{id}/analysis", MATCH_ID)
                .with(user(PLAYER))
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(result).bodyJson().extractingPath("$.matchId").isEqualTo(MATCH_ID.toString());
        assertThat(result).bodyJson().extractingPath("$.engine.depth").isEqualTo(16);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.moves[0].classification")
                .isEqualTo("BLUNDER");
        assertThat(result).bodyJson().extractingPath("$.moves[0].eval.mate").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.summary.source").isEqualTo("TEMPLATE");
        assertThat(result).bodyJson().extractingPath("$.errorCode").isNull();
    }

    @Test
    void getOfAnUnanalyzedGameIsNotFound() {
        when(analysisService.get(MATCH_ID)).thenThrow(new GameNotFoundException("not analyzed"));

        assertThat(mvcTester
                        .get()
                        .uri("/api/matches/{id}/analysis", MATCH_ID)
                        .with(user(PLAYER))
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aNewJobAnswers202() {
        when(analysisService.request(MATCH_ID, PLAYER.getId()))
                .thenReturn(new AnalysisService.RequestResult(queued(), true));

        MvcTestResult result = mvcTester
                .post()
                .uri("/api/matches/{id}/analysis", MATCH_ID)
                .with(user(PLAYER))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("QUEUED");
        assertThat(result).bodyJson().extractingPath("$.deadline").isEqualTo("2026-10-26T12:03:00Z");
    }

    @Test
    void anExistingJobAnswers200() {
        when(analysisService.request(MATCH_ID, PLAYER.getId()))
                .thenReturn(new AnalysisService.RequestResult(queued(), false));

        assertThat(mvcTester
                        .post()
                        .uri("/api/matches/{id}/analysis", MATCH_ID)
                        .with(user(PLAYER))
                        .exchange())
                .hasStatusOk();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(new AccessDeniedException("not a player"), HttpStatus.FORBIDDEN),
                Arguments.of(new GameNotFoundException("no such game"), HttpStatus.NOT_FOUND),
                Arguments.of(new TooManyAnalysesException("3 running"), HttpStatus.TOO_MANY_REQUESTS),
                Arguments.of(
                        new AnalysisUnavailableException("ANALYSIS_UNAVAILABLE", "down"),
                        HttpStatus.SERVICE_UNAVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void refusalsUseProblemDetails(RuntimeException failure, HttpStatus status) {
        when(analysisService.request(MATCH_ID, PLAYER.getId())).thenThrow(failure);

        MvcTestResult result = mvcTester
                .post()
                .uri("/api/matches/{id}/analysis", MATCH_ID)
                .with(user(PLAYER))
                .exchange();

        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void unavailableTellsTheClientWhetherTheServiceIsDownOrBusy() {
        when(analysisService.request(MATCH_ID, PLAYER.getId()))
                .thenThrow(new AnalysisUnavailableException("ANALYSIS_BUSY", "full"));

        MvcTestResult result = mvcTester
                .post()
                .uri("/api/matches/{id}/analysis", MATCH_ID)
                .with(user(PLAYER))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ANALYSIS_BUSY");
    }
}
