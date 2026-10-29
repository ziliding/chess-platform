package me.zilid.chessplatform.service;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.zilid.chessplatform.config.AnalysisConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests analyses of archived matches and applies their results. Not transactional on purpose: the job row must be
 * committed before its message is published, or a fast result could arrive for a job that does not exist yet.
 */
@Service
public class AnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);
    private static final TimeBasedEpochGenerator uuidGenerator = Generators.timeBasedEpochGenerator();
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final int SWEEP_BATCH_SIZE = 100;

    /** Error codes that come from Spring itself; the analysis service sends the others. */
    public static final String TIMED_OUT = "TIMED_OUT";

    public static final String UNAVAILABLE = "UNAVAILABLE";

    /**
     * The analysis for a request, and whether the request started it.
     *
     * @param created true if this call queued a new job, so the caller should answer 202
     */
    public record RequestResult(AnalysisResponse analysis, boolean created) {}

    private final GameAnalysisRepo analysisRepo;
    private final MatchRecordRepo matchRecordRepo;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final SimpMessagingTemplate messagingTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration deadline;
    private final int maxQueuedPerUser;

    public AnalysisService(
            GameAnalysisRepo analysisRepo,
            MatchRecordRepo matchRecordRepo,
            RabbitTemplate rabbitTemplate,
            AmqpAdmin amqpAdmin,
            SimpMessagingTemplate messagingTemplate,
            JsonMapper jsonMapper,
            Clock clock,
            @Value("${app.analysis.deadline}") Duration deadline,
            @Value("${app.analysis.max-queued-per-user}") int maxQueuedPerUser) {
        this.analysisRepo = analysisRepo;
        this.matchRecordRepo = matchRecordRepo;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.messagingTemplate = messagingTemplate;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.deadline = deadline;
        this.maxQueuedPerUser = maxQueuedPerUser;
    }

    public AnalysisResponse get(UUID matchId) {
        GameAnalysis analysis = analysisRepo
                .findByMatchId(matchId)
                .orElseThrow(() -> new GameNotFoundException("Game " + matchId + " has not been analyzed"));
        return toResponse(analysis);
    }

    /** Queue an analysis of the match for one of its players, unless one is already queued or done. */
    public RequestResult request(UUID matchId, UUID userId) {
        MatchParticipants match = matchRecordRepo
                .findParticipantsById(matchId)
                .orElseThrow(() -> new GameNotFoundException("Game with id " + matchId + " does not exist"));
        if (!match.isPlayer(userId)) {
            throw new AccessDeniedException("Only a player of the game can request its analysis");
        }
        GameAnalysis existing = analysisRepo.findByMatchId(matchId).orElse(null);
        // Before the limit below, so a double click gets its job back instead of an error
        if (existing != null && existing.getStatus() != AnalysisStatus.FAILED) {
            return new RequestResult(toResponse(existing), false);
        }
        // A soft limit: concurrent requests can exceed it by one
        if (analysisRepo.countByRequestedByAndStatus(userId, AnalysisStatus.QUEUED) >= maxQueuedPerUser) {
            throw new TooManyAnalysesException(
                    "You already have " + maxQueuedPerUser + " analyses running. Wait for one to finish.");
        }
        if (!hasConsumers()) {
            throw new AnalysisUnavailableException(
                    AnalysisUnavailableException.UNAVAILABLE, "Analysis is temporarily unavailable");
        }

        UUID jobId = uuidGenerator.generate();
        Instant jobDeadline = clock.instant().plus(deadline);
        int queued = existing == null
                ? analysisRepo.insertQueued(uuidGenerator.generate(), matchId, jobId, userId, jobDeadline)
                : analysisRepo.requeueFailed(matchId, jobId, userId, jobDeadline);
        if (queued == 0) {
            // Another request won the race; its job is the one to report
            return new RequestResult(get(matchId), false);
        }
        publish(new AnalysisRequestMessage(
                AnalysisRequestMessage.SCHEMA_VERSION, jobId, matchId, match.pgn(), jobDeadline));
        logger.info("Queued analysis job {} for match {}", jobId, matchId);
        return new RequestResult(get(matchId), true);
    }

    /** Apply a result from the analysis service, once; duplicates and results of replaced jobs are ignored. */
    public void applyResult(AnalysisResultMessage result) {
        int changed = switch (result.status()) {
            case "COMPLETED" -> analysisRepo.complete(result.jobId(), payloadJson(result));
            case "FAILED" -> analysisRepo.fail(result.jobId(), errorCode(result));
            default -> throw new AmqpRejectAndDontRequeueException("Unknown status: " + result.status());
        };
        if (changed == 0) {
            logger.info("Ignoring result of analysis job {}: it is no longer queued", result.jobId());
            return;
        }
        logger.info("Analysis job {} {}", result.jobId(), result.status());
        analysisRepo.findByJobId(result.jobId()).ifPresent(this::push);
    }

    /** Fail the jobs whose deadline has passed. Safe on every instance at once: each job is failed by one. */
    public void failExpired() {
        List<UUID> expired = analysisRepo.findExpiredJobIds(clock.instant(), PageRequest.of(0, SWEEP_BATCH_SIZE));
        for (UUID jobId : expired) {
            if (analysisRepo.fail(jobId, TIMED_OUT) == 1) {
                logger.warn("Analysis job {} timed out", jobId);
                analysisRepo.findByJobId(jobId).ifPresent(this::push);
            }
        }
    }

    private boolean hasConsumers() {
        try {
            QueueInformation queue = amqpAdmin.getQueueInfo(AnalysisConfig.REQUEST_QUEUE);
            return queue != null && queue.getConsumerCount() > 0;
        } catch (AmqpException e) {
            logger.warn("Cannot reach the analysis queue: {}", e.getMessage());
            return false;
        }
    }

    /** Publish and wait for the broker's confirm; a failure fails the job, since nobody will ever answer it. */
    private void publish(AnalysisRequestMessage request) {
        CorrelationData correlation = new CorrelationData(request.jobId().toString());
        String failure = AnalysisUnavailableException.UNAVAILABLE;
        try {
            rabbitTemplate.convertAndSend(
                    AnalysisConfig.EXCHANGE,
                    AnalysisConfig.REQUEST_KEY,
                    request,
                    message -> {
                        message.getMessageProperties()
                                .setMessageId(request.jobId().toString());
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (confirm.ack() && correlation.getReturned() == null) {
                return;
            }
            // A nack with a reachable broker means the queue is full; a returned message was not routed anywhere
            if (!confirm.ack()) {
                failure = AnalysisUnavailableException.BUSY;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | AmqpException e) {
            logger.warn("Publishing analysis job {} failed: {}", request.jobId(), e.toString());
        }
        failPublished(request.jobId(), failure);
    }

    private void failPublished(UUID jobId, String failure) {
        // A confirm that timed out does not prove the message was lost: its result may already have arrived
        if (analysisRepo.fail(jobId, UNAVAILABLE) == 0) {
            logger.info("Analysis job {} was already answered", jobId);
            return;
        }
        throw new AnalysisUnavailableException(
                failure,
                failure.equals(AnalysisUnavailableException.BUSY)
                        ? "The analysis queue is busy"
                        : "Analysis is temporarily unavailable");
    }

    private String payloadJson(AnalysisResultMessage result) {
        if (result.engine() == null
                || result.initialEval() == null
                || result.moves() == null
                || result.summary() == null) {
            throw new AmqpRejectAndDontRequeueException("COMPLETED result of " + result.jobId() + " has no analysis");
        }
        return jsonMapper.writeValueAsString(
                new AnalysisPayload(result.engine(), result.initialEval(), result.moves(), result.summary()));
    }

    private static String errorCode(AnalysisResultMessage result) {
        if (result.error() == null) {
            throw new AmqpRejectAndDontRequeueException("FAILED result of " + result.jobId() + " has no error");
        }
        return result.error().code();
    }

    private AnalysisResponse toResponse(GameAnalysis analysis) {
        AnalysisPayload payload = parsePayload(analysis.getResult());
        return new AnalysisResponse(
                analysis.getMatchId(),
                analysis.getStatus(),
                analysis.getErrorCode(),
                analysis.getDeadline(),
                analysis.getCompletedAt(),
                payload == null ? null : payload.engine(),
                payload == null ? null : payload.initialEval(),
                payload == null ? null : payload.moves(),
                payload == null ? null : payload.summary());
    }

    private @Nullable AnalysisPayload parsePayload(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, AnalysisPayload.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored analysis cannot be read", e);
        }
    }

    /** Tell both players. The push is only a notification: the client reloads with GET after a reconnect. */
    private void push(GameAnalysis analysis) {
        try {
            AnalysisResponse response = toResponse(analysis);
            matchRecordRepo.findParticipantsById(analysis.getMatchId()).ifPresent(match -> {
                // A player who deleted their account has no name to send to
                if (match.whiteName() != null) {
                    messagingTemplate.convertAndSendToUser(match.whiteName(), "/topic/analysis", response);
                }
                if (match.blackName() != null) {
                    messagingTemplate.convertAndSendToUser(match.blackName(), "/topic/analysis", response);
                }
            });
        } catch (RuntimeException e) {
            logger.warn("Could not push analysis of match {}", analysis.getMatchId(), e);
        }
    }
}
