package me.zilid.chessplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import me.zilid.chessplatform.exception.AnalysisUnavailableException;
import me.zilid.chessplatform.exception.GameNotFoundException;
import me.zilid.chessplatform.exception.TooManyAnalysesException;
import me.zilid.chessplatform.model.dto.AnalysisPayload;
import me.zilid.chessplatform.model.dto.AnalysisRequestMessage;
import me.zilid.chessplatform.model.dto.AnalysisResponse;
import me.zilid.chessplatform.model.dto.AnalysisResultMessage;
import me.zilid.chessplatform.model.entity.AnalysisStatus;
import me.zilid.chessplatform.model.entity.GameAnalysis;
import me.zilid.chessplatform.repository.GameAnalysisRepo;
import me.zilid.chessplatform.repository.MatchParticipants;
import me.zilid.chessplatform.repository.MatchRecordRepo;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

class AnalysisServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final UUID WHITE_ID = UUID.randomUUID();
    private static final UUID BLACK_ID = UUID.randomUUID();
    private static final MatchParticipants MATCH =
            new MatchParticipants(MATCH_ID, WHITE_ID, "alice", BLACK_ID, "bob", "1. e4 e5 2. Bc4");
    private static final AnalysisPayload PAYLOAD = new AnalysisPayload(
            new AnalysisPayload.Engine("Stockfish 17.1", 16),
            new AnalysisPayload.Eval(20, null),
            List.of(new AnalysisPayload.Move(
                    1, "e4", "e2e4", new AnalysisPayload.Eval(30, null), "e4", "e2e4", 0.0, "BEST", null)),
            new AnalysisPayload.Summary("White played well.", "TEMPLATE"));

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final GameAnalysisRepo analysisRepo = mock(GameAnalysisRepo.class);
    private final MatchRecordRepo matchRecordRepo = mock(MatchRecordRepo.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private AnalysisService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisService(
                analysisRepo,
                matchRecordRepo,
                rabbitTemplate,
                amqpAdmin,
                messagingTemplate,
                jsonMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(3),
                3);
        when(matchRecordRepo.findParticipantsById(MATCH_ID)).thenReturn(Optional.of(MATCH));
        when(amqpAdmin.getQueueInfo("analysis.requests")).thenReturn(new QueueInformation("analysis.requests", 0, 1));
        when(analysisRepo.insertQueued(any(), eq(MATCH_ID), any(), eq(WHITE_ID), any()))
                .thenReturn(1);
        when(analysisRepo.requeueFailed(eq(MATCH_ID), any(), eq(WHITE_ID), any()))
                .thenReturn(1);
    }

    // Answers instead of when(): a row is built inside other when() calls, where stubbing is not allowed
    private GameAnalysis row(AnalysisStatus status, @Nullable String result, @Nullable String errorCode) {
        return mock(
                GameAnalysis.class, invocation -> switch (invocation.getMethod().getName()) {
                    case "getMatchId" -> MATCH_ID;
                    case "getStatus" -> status;
                    case "getDeadline" -> NOW.plusSeconds(180);
                    case "getResult" -> result;
                    case "getErrorCode" -> errorCode;
                    default -> null;
                });
    }

    private GameAnalysis queuedRow() {
        return row(AnalysisStatus.QUEUED, null, null);
    }

    /** The row that exists once the new job has been inserted, as the final lookup sees it. */
    private void rowAfterInsert() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty()).thenReturn(Optional.of(queuedRow()));
    }

    /** Make the broker answer the publish with {@code answer} (null: never answers). */
    private void brokerConfirms(@Nullable Consumer<CorrelationData> answer) {
        doAnswer(invocation -> {
                    if (answer != null) {
                        answer.accept(invocation.getArgument(4, CorrelationData.class));
                    }
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        eq("analysis"),
                        eq("request"),
                        any(Object.class),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
    }

    private static Consumer<CorrelationData> ack() {
        return correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
    }

    private static AnalysisResultMessage completed(UUID jobId) {
        return new AnalysisResultMessage(
                1,
                jobId,
                MATCH_ID,
                "COMPLETED",
                PAYLOAD.engine(),
                PAYLOAD.initialEval(),
                PAYLOAD.moves(),
                PAYLOAD.summary(),
                null);
    }

    private static AnalysisResultMessage failed(UUID jobId, String code) {
        return new AnalysisResultMessage(
                1, jobId, MATCH_ID, "FAILED", null, null, null, null, new AnalysisResultMessage.ErrorInfo(code, "why"));
    }

    @Test
    void queuesAJobAndPublishesItAfterTheRowIsStored() {
        rowAfterInsert();
        brokerConfirms(ack());

        AnalysisService.RequestResult result = service.request(MATCH_ID, WHITE_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.analysis().status()).isEqualTo(AnalysisStatus.QUEUED);
        ArgumentCaptor<UUID> jobId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(analysisRepo).insertQueued(any(), eq(MATCH_ID), jobId.capture(), eq(WHITE_ID), deadline.capture());
        assertThat(deadline.getValue()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq("analysis"), eq("request"), sent.capture(), post.capture(), any(CorrelationData.class));
        assertThat(sent.getValue())
                .isEqualTo(new AnalysisRequestMessage(
                        1, jobId.getValue(), MATCH_ID, "1. e4 e5 2. Bc4", NOW.plus(Duration.ofMinutes(3))));
        MessageProperties properties = new MessageProperties();
        Message processed = post.getValue().postProcessMessage(new Message(new byte[0], properties));
        assertThat(processed.getMessageProperties().getMessageId())
                .isEqualTo(jobId.getValue().toString());
    }

    @Test
    void aFailedAnalysisIsRetriedUnderANewJob() {
        GameAnalysis failedRow = row(AnalysisStatus.FAILED, null, "TIMED_OUT");
        when(analysisRepo.findByMatchId(MATCH_ID))
                .thenReturn(Optional.of(failedRow))
                .thenReturn(Optional.of(failedRow))
                .thenReturn(Optional.of(queuedRow()));
        brokerConfirms(ack());

        AnalysisService.RequestResult result = service.request(MATCH_ID, WHITE_ID);

        assertThat(result.created()).isTrue();
        verify(analysisRepo).requeueFailed(eq(MATCH_ID), any(), eq(WHITE_ID), any());
        verify(analysisRepo, never()).insertQueued(any(), any(), any(), any(), any());
    }

    @Test
    void anAnalysisThatIsQueuedOrDoneIsReturnedWithoutANewJob() {
        for (AnalysisStatus status : List.of(AnalysisStatus.QUEUED, AnalysisStatus.COMPLETED)) {
            String result = status == AnalysisStatus.COMPLETED ? jsonMapper.writeValueAsString(PAYLOAD) : null;
            when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.of(row(status, result, null)));

            AnalysisService.RequestResult requested = service.request(MATCH_ID, BLACK_ID);

            assertThat(requested.created()).isFalse();
            assertThat(requested.analysis().status()).isEqualTo(status);
        }
        verifyNoInteractions(rabbitTemplate);
        verify(analysisRepo, never()).insertQueued(any(), any(), any(), any(), any());
    }

    @Test
    void aDoubleClickGetsItsJobBackEvenAtTheLimit() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.of(queuedRow()));
        when(analysisRepo.countByRequestedByAndStatus(WHITE_ID, AnalysisStatus.QUEUED))
                .thenReturn(3L);

        assertThat(service.request(MATCH_ID, WHITE_ID).created()).isFalse();
    }

    @Test
    void unknownMatchIsNotFound() {
        UUID other = UUID.randomUUID();
        when(matchRecordRepo.findParticipantsById(other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(other, WHITE_ID)).isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void onlyAPlayerCanRequest() {
        assertThatThrownBy(() -> service.request(MATCH_ID, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void aUserCannotHaveMoreThanTheLimitQueued() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());
        when(analysisRepo.countByRequestedByAndStatus(WHITE_ID, AnalysisStatus.QUEUED))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(TooManyAnalysesException.class);
        verify(analysisRepo, never()).insertQueued(any(), any(), any(), any(), any());
    }

    @Test
    void withoutConsumersNothingIsStored() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());
        when(amqpAdmin.getQueueInfo("analysis.requests")).thenReturn(new QueueInformation("analysis.requests", 0, 0));

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID))
                .isInstanceOfSatisfying(
                        AnalysisUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ANALYSIS_UNAVAILABLE"));
        verify(analysisRepo, never()).insertQueued(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingQueueOrBrokerMeansUnavailable() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());
        when(amqpAdmin.getQueueInfo("analysis.requests")).thenReturn(null);
        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(AnalysisUnavailableException.class);

        doThrow(new AmqpConnectException(new RuntimeException("down")))
                .when(amqpAdmin)
                .getQueueInfo("analysis.requests");
        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(AnalysisUnavailableException.class);
        verify(analysisRepo, never()).insertQueued(any(), any(), any(), any(), any());
    }

    @Test
    void whenAnotherRequestWonTheRaceItsJobIsReturned() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty()).thenReturn(Optional.of(queuedRow()));
        when(analysisRepo.insertQueued(any(), eq(MATCH_ID), any(), eq(WHITE_ID), any()))
                .thenReturn(0);

        assertThat(service.request(MATCH_ID, WHITE_ID).created()).isFalse();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void aNackedPublishFailsTheJobAndReportsABusyQueue() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());
        when(analysisRepo.fail(any(), eq("UNAVAILABLE"))).thenReturn(1);
        brokerConfirms(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "full")));

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID))
                .isInstanceOfSatisfying(
                        AnalysisUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ANALYSIS_BUSY"));
        verify(analysisRepo).fail(any(), eq("UNAVAILABLE"));
    }

    @Test
    void anUnroutableMessageFailsTheJob() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());
        when(analysisRepo.fail(any(), eq("UNAVAILABLE"))).thenReturn(1);
        brokerConfirms(correlation -> {
            correlation.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                    new Message(new byte[0]), 312, "NO_ROUTE", "analysis", "request"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID))
                .isInstanceOfSatisfying(
                        AnalysisUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ANALYSIS_UNAVAILABLE"));
    }

    @Test
    void aBrokerThatNeverConfirmsFailsTheJob() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());
        when(analysisRepo.fail(any(), eq("UNAVAILABLE"))).thenReturn(1);
        brokerConfirms(correlation -> correlation.getFuture().completeExceptionally(new RuntimeException("lost")));

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(AnalysisUnavailableException.class);
    }

    @Test
    void aBrokerOutageAtPublishFailsTheJob() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());
        when(analysisRepo.fail(any(), eq("UNAVAILABLE"))).thenReturn(1);
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(rabbitTemplate)
                .convertAndSend(
                        eq("analysis"),
                        eq("request"),
                        any(Object.class),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID))
                .isInstanceOfSatisfying(
                        AnalysisUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ANALYSIS_UNAVAILABLE"));
        verify(analysisRepo).fail(any(), eq("UNAVAILABLE"));
    }

    @Test
    void aPublishFailureAfterTheResultArrivedIsNotAnError() {
        rowAfterInsert();
        when(analysisRepo.fail(any(), eq("UNAVAILABLE"))).thenReturn(0);
        brokerConfirms(correlation -> correlation.getFuture().completeExceptionally(new RuntimeException("slow")));

        assertThat(service.request(MATCH_ID, WHITE_ID).created()).isTrue();
    }

    @Test
    void getReturnsTheStoredAnalysis() throws Exception {
        when(analysisRepo.findByMatchId(MATCH_ID))
                .thenReturn(Optional.of(row(AnalysisStatus.COMPLETED, jsonMapper.writeValueAsString(PAYLOAD), null)));

        AnalysisResponse response = service.get(MATCH_ID);

        assertThat(response.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(response.moves()).isEqualTo(PAYLOAD.moves());
        assertThat(Objects.requireNonNull(response.summary()).text()).isEqualTo("White played well.");
    }

    @Test
    void getOfAnUnanalyzedMatchIsNotFound() {
        when(analysisRepo.findByMatchId(MATCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MATCH_ID)).isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void aCompletedResultIsStoredAndPushedToBothPlayers() {
        UUID jobId = UUID.randomUUID();
        when(analysisRepo.complete(eq(jobId), anyString())).thenReturn(1);
        when(analysisRepo.findByJobId(jobId))
                .thenReturn(Optional.of(row(AnalysisStatus.COMPLETED, jsonMapper.writeValueAsString(PAYLOAD), null)));

        service.applyResult(completed(jobId));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(analysisRepo).complete(eq(jobId), stored.capture());
        assertThat(jsonMapper.readValue(stored.getValue(), AnalysisPayload.class))
                .isEqualTo(PAYLOAD);
        verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/topic/analysis"), any(AnalysisResponse.class));
        verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/topic/analysis"), any(AnalysisResponse.class));
    }

    @Test
    void aFailedResultStoresItsErrorCode() {
        UUID jobId = UUID.randomUUID();
        when(analysisRepo.fail(jobId, "INVALID_PGN")).thenReturn(1);
        when(analysisRepo.findByJobId(jobId)).thenReturn(Optional.of(row(AnalysisStatus.FAILED, null, "INVALID_PGN")));

        service.applyResult(failed(jobId, "INVALID_PGN"));

        verify(analysisRepo).fail(jobId, "INVALID_PGN");
        verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/topic/analysis"), any(AnalysisResponse.class));
    }

    @Test
    void aDuplicateOrStaleResultChangesAndPushesNothing() {
        UUID jobId = UUID.randomUUID();
        when(analysisRepo.complete(eq(jobId), anyString())).thenReturn(0);

        service.applyResult(completed(jobId));

        verify(analysisRepo, never()).findByJobId(any());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void malformedResultsAreRejectedWithoutRequeue() {
        UUID jobId = UUID.randomUUID();
        AnalysisResultMessage noMoves = new AnalysisResultMessage(
                1,
                jobId,
                MATCH_ID,
                "COMPLETED",
                PAYLOAD.engine(),
                PAYLOAD.initialEval(),
                null,
                PAYLOAD.summary(),
                null);
        AnalysisResultMessage noError =
                new AnalysisResultMessage(1, jobId, MATCH_ID, "FAILED", null, null, null, null, null);
        AnalysisResultMessage unknown =
                new AnalysisResultMessage(1, jobId, MATCH_ID, "RUNNING", null, null, null, null, null);

        for (AnalysisResultMessage message : List.of(noMoves, noError, unknown)) {
            assertThatThrownBy(() -> service.applyResult(message))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void aFailingPushDoesNotLoseTheStoredResult() {
        UUID jobId = UUID.randomUUID();
        when(analysisRepo.fail(jobId, "ENGINE_ERROR")).thenReturn(1);
        when(analysisRepo.findByJobId(jobId)).thenReturn(Optional.of(row(AnalysisStatus.FAILED, null, "ENGINE_ERROR")));
        doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));

        service.applyResult(failed(jobId, "ENGINE_ERROR"));

        verify(analysisRepo).fail(jobId, "ENGINE_ERROR");
    }

    @Test
    void theSweeperFailsExpiredJobsAndPushesOnlyTheOnesItFailed() {
        UUID mine = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();
        when(analysisRepo.findExpiredJobIds(eq(NOW), any(Pageable.class))).thenReturn(List.of(mine, someoneElses));
        when(analysisRepo.fail(mine, "TIMED_OUT")).thenReturn(1);
        when(analysisRepo.fail(someoneElses, "TIMED_OUT")).thenReturn(0);
        when(analysisRepo.findByJobId(mine)).thenReturn(Optional.of(row(AnalysisStatus.FAILED, null, "TIMED_OUT")));

        service.failExpired();

        verify(analysisRepo, never()).findByJobId(someoneElses);
        verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/topic/analysis"), any(AnalysisResponse.class));
    }
}
