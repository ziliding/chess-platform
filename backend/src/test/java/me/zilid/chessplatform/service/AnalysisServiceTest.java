package me.zilid.chessplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import me.zilid.chessplatform.repository.MatchParticipants;
import me.zilid.chessplatform.repository.MatchRecordRepo;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;

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

    private final AnalysisJobs jobs = mock(AnalysisJobs.class);
    private final MatchRecordRepo matchRecordRepo = mock(MatchRecordRepo.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private AnalysisService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisService(
                jobs,
                matchRecordRepo,
                rabbitTemplate,
                amqpAdmin,
                messagingTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(3),
                3);
        when(matchRecordRepo.findParticipantsById(MATCH_ID)).thenReturn(Optional.of(MATCH));
        when(amqpAdmin.getQueueInfo("analysis.requests")).thenReturn(new QueueInformation("analysis.requests", 0, 1));
        when(jobs.find(MATCH_ID)).thenReturn(Optional.empty());
        when(jobs.queue(eq(MATCH_ID), any(), eq(WHITE_ID), any())).thenReturn(Optional.of(queued()));
    }

    private static AnalysisResponse analysis(AnalysisStatus status, @Nullable String errorCode, boolean retryable) {
        boolean done = status == AnalysisStatus.COMPLETED;
        return new AnalysisResponse(
                MATCH_ID,
                status,
                errorCode,
                retryable,
                NOW.plusSeconds(180),
                done ? NOW : null,
                done ? PAYLOAD.engine() : null,
                done ? PAYLOAD.initialEval() : null,
                done ? PAYLOAD.moves() : null,
                done ? PAYLOAD.summary() : null);
    }

    private static AnalysisResponse queued() {
        return analysis(AnalysisStatus.QUEUED, null, false);
    }

    private static AnalysisResponse failed(String errorCode, boolean retryable) {
        return analysis(AnalysisStatus.FAILED, errorCode, retryable);
    }

    /** The analysis once the job is queued, as the final lookup sees it. */
    private void queuedAfterwards() {
        when(jobs.find(MATCH_ID)).thenReturn(Optional.empty()).thenReturn(Optional.of(queued()));
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
        queuedAfterwards();
        brokerConfirms(ack());

        AnalysisService.RequestResult result = service.request(MATCH_ID, WHITE_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.analysis().status()).isEqualTo(AnalysisStatus.QUEUED);
        ArgumentCaptor<UUID> jobId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        InOrder inOrder = inOrder(jobs, rabbitTemplate);
        inOrder.verify(jobs).queue(eq(MATCH_ID), jobId.capture(), eq(WHITE_ID), deadline.capture());
        assertThat(deadline.getValue()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        inOrder.verify(rabbitTemplate)
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
    void aRetryableFailureIsRetriedUnderANewJob() {
        when(jobs.find(MATCH_ID))
                .thenReturn(Optional.of(failed("TIMED_OUT", true)))
                .thenReturn(Optional.of(queued()));
        brokerConfirms(ack());

        AnalysisService.RequestResult result = service.request(MATCH_ID, WHITE_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.analysis().status()).isEqualTo(AnalysisStatus.QUEUED);
        verify(jobs).queue(eq(MATCH_ID), any(), eq(WHITE_ID), any());
    }

    @Test
    void anAnalysisThatIsQueuedDoneOrFailedForGoodIsReturnedWithoutANewJob() {
        for (AnalysisResponse existing :
                List.of(queued(), analysis(AnalysisStatus.COMPLETED, null, false), failed("INVALID_PGN", false))) {
            when(jobs.find(MATCH_ID)).thenReturn(Optional.of(existing));

            AnalysisService.RequestResult requested = service.request(MATCH_ID, BLACK_ID);

            assertThat(requested.created()).isFalse();
            assertThat(requested.analysis()).isEqualTo(existing);
        }
        verifyNoInteractions(rabbitTemplate);
        verify(jobs, never()).queue(any(), any(), any(), any());
    }

    @Test
    void aDoubleClickGetsItsJobBackEvenAtTheLimit() {
        when(jobs.find(MATCH_ID)).thenReturn(Optional.of(queued()));
        when(jobs.countQueued(WHITE_ID)).thenReturn(3L);

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
        when(jobs.countQueued(WHITE_ID)).thenReturn(3L);

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(TooManyAnalysesException.class);
        verify(jobs, never()).queue(any(), any(), any(), any());
    }

    @Test
    void withoutConsumersNothingIsStored() {
        when(amqpAdmin.getQueueInfo("analysis.requests")).thenReturn(new QueueInformation("analysis.requests", 0, 0));

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID))
                .isInstanceOfSatisfying(
                        AnalysisUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ANALYSIS_UNAVAILABLE"));
        verify(jobs, never()).queue(any(), any(), any(), any());
    }

    @Test
    void aMissingQueueOrBrokerMeansUnavailable() {
        when(amqpAdmin.getQueueInfo("analysis.requests")).thenReturn(null);
        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(AnalysisUnavailableException.class);

        doThrow(new AmqpConnectException(new RuntimeException("down")))
                .when(amqpAdmin)
                .getQueueInfo("analysis.requests");
        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(AnalysisUnavailableException.class);
        verify(jobs, never()).queue(any(), any(), any(), any());
    }

    @Test
    void whenAnotherRequestWonTheRaceItsJobIsReturned() {
        queuedAfterwards();
        when(jobs.queue(eq(MATCH_ID), any(), eq(WHITE_ID), any())).thenReturn(Optional.empty());

        assertThat(service.request(MATCH_ID, WHITE_ID).created()).isFalse();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void aNackedPublishFailsTheJobAndReportsABusyQueue() {
        when(jobs.fail(any(), eq("UNAVAILABLE"))).thenReturn(Optional.of(failed("UNAVAILABLE", true)));
        brokerConfirms(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "full")));

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID))
                .isInstanceOfSatisfying(
                        AnalysisUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ANALYSIS_BUSY"));
        verify(jobs).fail(any(), eq("UNAVAILABLE"));
    }

    @Test
    void anUnroutableMessageFailsTheJob() {
        when(jobs.fail(any(), eq("UNAVAILABLE"))).thenReturn(Optional.of(failed("UNAVAILABLE", true)));
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
        when(jobs.fail(any(), eq("UNAVAILABLE"))).thenReturn(Optional.of(failed("UNAVAILABLE", true)));
        brokerConfirms(correlation -> correlation.getFuture().completeExceptionally(new RuntimeException("lost")));

        assertThatThrownBy(() -> service.request(MATCH_ID, WHITE_ID)).isInstanceOf(AnalysisUnavailableException.class);
    }

    @Test
    void aBrokerOutageAtPublishFailsTheJob() {
        when(jobs.fail(any(), eq("UNAVAILABLE"))).thenReturn(Optional.of(failed("UNAVAILABLE", true)));
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
        verify(jobs).fail(any(), eq("UNAVAILABLE"));
    }

    @Test
    void aPublishFailureAfterTheResultArrivedIsNotAnError() {
        queuedAfterwards();
        when(jobs.fail(any(), eq("UNAVAILABLE"))).thenReturn(Optional.empty());
        brokerConfirms(correlation -> correlation.getFuture().completeExceptionally(new RuntimeException("slow")));

        assertThat(service.request(MATCH_ID, WHITE_ID).created()).isTrue();
    }

    @Test
    void getReturnsTheStoredAnalysis() {
        AnalysisResponse done = analysis(AnalysisStatus.COMPLETED, null, false);
        when(jobs.find(MATCH_ID)).thenReturn(Optional.of(done));

        assertThat(service.get(MATCH_ID)).isEqualTo(done);
    }

    @Test
    void getOfAnUnanalyzedMatchIsNotFound() {
        assertThatThrownBy(() -> service.get(MATCH_ID)).isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void aCompletedResultIsStoredAndPushedToBothPlayers() {
        UUID jobId = UUID.randomUUID();
        AnalysisResponse done = analysis(AnalysisStatus.COMPLETED, null, false);
        when(jobs.complete(jobId, PAYLOAD)).thenReturn(Optional.of(done));

        service.applyResult(completed(jobId));

        verify(jobs).complete(jobId, PAYLOAD);
        verify(messagingTemplate).convertAndSendToUser("alice", "/topic/analysis", done);
        verify(messagingTemplate).convertAndSendToUser("bob", "/topic/analysis", done);
    }

    @Test
    void aFailedResultStoresItsErrorCode() {
        UUID jobId = UUID.randomUUID();
        when(jobs.fail(jobId, "INVALID_PGN")).thenReturn(Optional.of(failed("INVALID_PGN", false)));

        service.applyResult(failed(jobId, "INVALID_PGN"));

        verify(jobs).fail(jobId, "INVALID_PGN");
        verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/topic/analysis"), any(AnalysisResponse.class));
    }

    @Test
    void aDuplicateOrStaleResultPushesNothing() {
        UUID jobId = UUID.randomUUID();
        when(jobs.complete(jobId, PAYLOAD)).thenReturn(Optional.empty());

        service.applyResult(completed(jobId));

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
        when(jobs.fail(jobId, "ENGINE_ERROR")).thenReturn(Optional.of(failed("ENGINE_ERROR", true)));
        doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));

        service.applyResult(failed(jobId, "ENGINE_ERROR"));

        verify(jobs).fail(jobId, "ENGINE_ERROR");
    }

    @Test
    void theSweeperPushesTheJobsItFailed() {
        when(jobs.failExpired(NOW, 100)).thenReturn(List.of(failed("TIMED_OUT", true)));

        service.failExpired();

        verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/topic/analysis"), any(AnalysisResponse.class));
    }
}
