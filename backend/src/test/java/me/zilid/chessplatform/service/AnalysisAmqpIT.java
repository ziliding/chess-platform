package me.zilid.chessplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.zilid.chessplatform.config.ContainersConfig;
import me.zilid.chessplatform.config.PostgresConfig;
import me.zilid.chessplatform.model.entity.MatchRecord;
import me.zilid.chessplatform.repository.MatchRecordRepo;
import me.zilid.chessplatform.repository.UserRepo;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whole path with real Postgres, Redis and RabbitMQ: request, queue, a stand-in for the Python service, result
 * listener, database and the STOMP push to the players. The analysis service's own behaviour is tested in
 * {@code analysis/}; here it is a listener that answers from the contract fixtures.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=-1",
            "app.analysis.deadline=4s",
            "app.analysis.sweep.interval=200ms",
            "app.game.timeout-sweep.enabled=false"
        })
@Import({ContainersConfig.class, PostgresConfig.class})
@ActiveProfiles("test")
class AnalysisAmqpIT {
    private static final Path FIXTURES = Path.of("..", "contracts", "analysis", "v1");
    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "securePassword123";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private enum Behaviour {
        ANSWER,
        STAY_SILENT
    }

    private record Player(UUID id, String username, String session) {}

    @LocalServerPort
    private int port;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private MatchRecordRepo matchRecordRepo;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<StompSession> sessions = new CopyOnWriteArrayList<>();
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Behaviour> behaviour = new AtomicReference<>(Behaviour.ANSWER);
    private final AtomicReference<String> answerWith = new AtomicReference<>("result.completed.json");
    private SimpleMessageListenerContainer fakeAnalysisService;
    private WebSocketStompClient stompClient;
    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        stompClient.setTaskScheduler(scheduler); // needed to track receipts
        fakeAnalysisService = new SimpleMessageListenerContainer(connectionFactory);
        fakeAnalysisService.setQueueNames("analysis.requests");
        fakeAnalysisService.setMessageListener(this::onRequest);
        fakeAnalysisService.start();
        await("fake analysis service consuming").atMost(TIMEOUT).until(() -> consumers() > 0);
        received.clear();
        behaviour.set(Behaviour.ANSWER);
        answerWith.set("result.completed.json");
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(StompSession::disconnect);
        stompClient.stop();
        fakeAnalysisService.stop();
    }

    private int consumers() {
        var info = amqpAdmin.getQueueInfo("analysis.requests");
        return info == null ? 0 : info.getConsumerCount();
    }

    /** What the Python service does: read the request, answer with a result for the same job. */
    private void onRequest(Message request) {
        JsonNode body = jsonMapper.readTree(request.getBody());
        received.add(body);
        if (behaviour.get() == Behaviour.STAY_SILENT) {
            return;
        }
        try {
            ObjectNode result =
                    (ObjectNode) jsonMapper.readTree(Files.readAllBytes(FIXTURES.resolve(answerWith.get())));
            result.put("jobId", body.get("jobId").asString());
            result.put("matchId", body.get("matchId").asString());
            rabbitTemplate.send("analysis", "result", json(jsonMapper.writeValueAsBytes(result)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Message json(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(body, properties);
    }

    private Player register(String prefix) {
        String username = prefix + UUID.randomUUID().toString().substring(0, 8);
        http.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "email", username + "@example.com", "rawPassword", PASSWORD))
                .retrieve()
                .toBodilessEntity();
        var login = http.post()
                .uri("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", PASSWORD))
                .retrieve()
                .toEntity(JsonNode.class);
        String cookie = Objects.requireNonNull(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        UUID id = UUID.fromString(
                Objects.requireNonNull(login.getBody()).get("id").asString());
        return new Player(id, username, cookie.substring(0, cookie.indexOf(';')));
    }

    private UUID archivedMatch(Player white, Player black) throws IOException {
        String pgn = jsonMapper
                .readTree(Files.readAllBytes(FIXTURES.resolve("request.platform-pgn.json")))
                .get("pgn")
                .asString();
        Instant end = Instant.now();
        MatchRecord match = new MatchRecord(
                userRepo.getReferenceById(white.id()),
                userRepo.getReferenceById(black.id()),
                "1-0",
                "CHECKMATE",
                pgn,
                end.minusSeconds(60),
                end);
        return matchRecordRepo.save(match).getId();
    }

    private RestClient.ResponseSpec post(Player player, UUID matchId) {
        return http.post()
                .uri("/api/games/{id}/analysis", matchId)
                .header(HttpHeaders.COOKIE, player.session())
                .retrieve();
    }

    private JsonNode get(Player player, UUID matchId) {
        return Objects.requireNonNull(http.get()
                .uri("/api/games/{id}/analysis", matchId)
                .header(HttpHeaders.COOKIE, player.session())
                .retrieve()
                .body(JsonNode.class));
    }

    /** Subscribes to the player's private analysis updates and waits until the broker has the subscription. */
    private BlockingQueue<JsonNode> listen(Player player) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin(ORIGIN);
        handshake.add(HttpHeaders.COOKIE, player.session());
        StompSession session = stompClient
                .connectAsync(
                        URI.create("ws://localhost:" + port + "/ws"),
                        handshake,
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() {})
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        sessions.add(session);
        session.setAutoReceipt(true);
        BlockingQueue<JsonNode> updates = new LinkedBlockingQueue<>();
        StompSession.Subscription subscription = session.subscribe("/user/topic/analysis", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                updates.add((JsonNode) Objects.requireNonNull(payload));
            }
        });
        CountDownLatch subscribed = new CountDownLatch(1);
        subscription.addReceiptTask(subscribed::countDown);
        assertThat(subscribed.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        return updates;
    }

    private static JsonNode next(BlockingQueue<JsonNode> updates) throws InterruptedException {
        JsonNode update = updates.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(update).as("an analysis update within %s", TIMEOUT).isNotNull();
        return Objects.requireNonNull(update);
    }

    @ParameterizedTest
    @ValueSource(strings = {"result.completed.json", "result.completed.extra-field.json"})
    void aRequestedAnalysisIsComputedStoredAndPushedToBothPlayers(String fixture) throws Exception {
        answerWith.set(fixture);
        Player alice = register("alice");
        Player bob = register("bob");
        UUID matchId = archivedMatch(alice, bob);
        BlockingQueue<JsonNode> alicesUpdates = listen(alice);
        BlockingQueue<JsonNode> bobsUpdates = listen(bob);

        var accepted = post(alice, matchId).toEntity(JsonNode.class);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(Objects.requireNonNull(accepted.getBody()).get("status").asString())
                .isEqualTo("QUEUED");
        for (BlockingQueue<JsonNode> updates : List.of(alicesUpdates, bobsUpdates)) {
            JsonNode update = next(updates);
            assertThat(update.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(update.get("matchId").asString()).isEqualTo(matchId.toString());
            assertThat(update.get("moves")).hasSize(7);
        }
        JsonNode stored = get(bob, matchId);
        assertThat(stored.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(stored.get("moves").get(5).get("classification").asString()).isEqualTo("BLUNDER");
        assertThat(stored.get("summary").get("text").asString()).contains("White won by checkmate");
        // The request the service got is the contract's: this match's PGN, under a deadline
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(received.getFirst().get("pgn").asString()).contains("4. Qxf7#");
    }

    @Test
    void askingAgainDoesNotQueueAnotherJob() throws Exception {
        Player alice = register("alice");
        Player bob = register("bob");
        UUID matchId = archivedMatch(alice, bob);
        BlockingQueue<JsonNode> updates = listen(alice);
        post(alice, matchId).toBodilessEntity();
        next(updates);

        var again = post(bob, matchId).toEntity(JsonNode.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(again.getBody()).get("status").asString())
                .isEqualTo("COMPLETED");
        assertThat(received).hasSize(1);
    }

    @Test
    void onlyAPlayerOfTheGameCanRequestItsAnalysis() throws Exception {
        Player alice = register("alice");
        Player bob = register("bob");
        Player stranger = register("stranger");
        UUID matchId = archivedMatch(alice, bob);

        var refused = post(stranger, matchId)
                .onStatus(status -> true, (request, response) -> {})
                .toBodilessEntity();

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(received).isEmpty();
        var notAnalyzed = http.get()
                .uri("/api/games/{id}/analysis", matchId)
                .header(HttpHeaders.COOKIE, stranger.session())
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toBodilessEntity();
        assertThat(notAnalyzed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void withoutTheAnalysisServiceTheRequestIsRefusedAndNothingIsKept() throws Exception {
        Player alice = register("alice");
        Player bob = register("bob");
        UUID matchId = archivedMatch(alice, bob);
        fakeAnalysisService.stop();
        await("consumer gone").atMost(TIMEOUT).until(() -> consumers() == 0);

        var refused = post(alice, matchId)
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(JsonNode.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(Objects.requireNonNull(refused.getBody()).get("code").asString())
                .isEqualTo("ANALYSIS_UNAVAILABLE");
        var notAnalyzed = http.get()
                .uri("/api/games/{id}/analysis", matchId)
                .header(HttpHeaders.COOKIE, alice.session())
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toBodilessEntity();
        assertThat(notAnalyzed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ajobNobodyAnswersTimesOutAndCanBeRetried() throws Exception {
        behaviour.set(Behaviour.STAY_SILENT);
        Player alice = register("alice");
        Player bob = register("bob");
        UUID matchId = archivedMatch(alice, bob);
        BlockingQueue<JsonNode> updates = listen(alice);
        post(alice, matchId).toBodilessEntity();

        JsonNode timedOut = next(updates);

        assertThat(timedOut.get("status").asString()).isEqualTo("FAILED");
        assertThat(timedOut.get("errorCode").asString()).isEqualTo("TIMED_OUT");
        assertThat(get(bob, matchId).get("status").asString()).isEqualTo("FAILED");

        // The service is back: the retry gets a new job and completes
        behaviour.set(Behaviour.ANSWER);
        var retried = post(alice, matchId).toEntity(JsonNode.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(next(updates).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(received).hasSize(2);
        assertThat(received.get(1).get("jobId")).isNotEqualTo(received.get(0).get("jobId"));
    }

    @Test
    void aResultThatCannotBeUnderstoodEndsUpInTheDeadLetterQueue() {
        drain("analysis.results.dlq");
        rabbitTemplate.send("analysis", "result", json("{\"jobId\": \"not-a-uuid\"}".getBytes()));
        // Understood, but a COMPLETED result with no analysis in it: retried, then rejected
        String hollow = "{\"schemaVersion\":1,\"jobId\":\"%s\",\"matchId\":\"%s\",\"status\":\"COMPLETED\"}"
                .formatted(UUID.randomUUID(), UUID.randomUUID());
        rabbitTemplate.send("analysis", "result", json(hollow.getBytes()));

        List<Message> deadLetters = new CopyOnWriteArrayList<>();
        await("both messages dead-lettered").atMost(TIMEOUT).until(() -> {
            Message message = rabbitTemplate.receive("analysis.results.dlq");
            if (message != null) {
                deadLetters.add(message);
            }
            return deadLetters.size() == 2;
        });
    }

    @Test
    void aFullQueueRefusesTheRequestAsBusy() throws Exception {
        Player alice = register("alice");
        Player bob = register("bob");
        UUID matchId = archivedMatch(alice, bob);
        // A worker that takes one job and never finishes it keeps the queue's consumer while the backlog fills
        fakeAnalysisService.stop();
        Connection connection = connectionFactory.createConnection();
        Channel stuckWorker = connection.createChannel(false);
        try {
            stuckWorker.basicQos(1);
            stuckWorker.basicConsume("analysis.requests", false, new DefaultConsumer(stuckWorker));
            await("stuck worker consuming").atMost(TIMEOUT).until(() -> consumers() > 0);
            // Past the limit, and confirmed, so the queue is rejecting publishes by the time the request comes
            rabbitTemplate.invoke(operations -> {
                for (int i = 0; i < 1100; i++) {
                    operations.send("analysis", "request", json("{}".getBytes()));
                }
                operations.waitForConfirms(TIMEOUT.toMillis());
                return null;
            });

            var refused = post(alice, matchId)
                    .onStatus(status -> true, (request, response) -> {})
                    .toEntity(JsonNode.class);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(Objects.requireNonNull(refused.getBody()).get("code").asString())
                    .isEqualTo("ANALYSIS_BUSY");
            JsonNode stored = get(alice, matchId);
            assertThat(stored.get("status").asString()).isEqualTo("FAILED");
            assertThat(stored.get("errorCode").asString()).isEqualTo("UNAVAILABLE");
        } finally {
            stuckWorker.close(); // returns its message to the queue, so purge after
            connection.close();
            amqpAdmin.purgeQueue("analysis.requests", false);
        }
    }

    @Test
    void aDuplicateOrLateResultChangesNothing() throws Exception {
        Player alice = register("alice");
        Player bob = register("bob");
        UUID matchId = archivedMatch(alice, bob);
        BlockingQueue<JsonNode> updates = listen(alice);
        post(alice, matchId).toBodilessEntity();
        JsonNode completed = next(updates);
        String jobId = received.getFirst().get("jobId").asString();

        // The same result again (a redelivered request), a failure arriving after it, and an old job's result
        sendResult("result.completed.json", jobId, matchId);
        sendResult("result.failed.json", jobId, matchId);
        sendResult("result.failed.json", UUID.randomUUID().toString(), matchId);

        assertThat(updates.poll(2, TimeUnit.SECONDS)).as("no further push").isNull();
        JsonNode stored = get(bob, matchId);
        assertThat(stored.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(stored.get("completedAt")).isEqualTo(completed.get("completedAt"));
    }

    @Test
    void aResultThatCannotBeStoredIsRetriedThenDeadLetteredAndTheJobTimesOut() throws Exception {
        behaviour.set(Behaviour.STAY_SILENT);
        Player alice = register("alice");
        Player bob = register("bob");
        UUID matchId = archivedMatch(alice, bob);
        BlockingQueue<JsonNode> updates = listen(alice);
        post(alice, matchId).toBodilessEntity();
        await("request received").atMost(TIMEOUT).until(() -> !received.isEmpty());
        String jobId = received.getFirst().get("jobId").asString();
        drain("analysis.results.dlq");
        // The database refuses every write to this job, for every test context listening on the results queue
        jdbcTemplate.execute("""
                CREATE FUNCTION refuse_job() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'database unavailable'; END $$;
                CREATE TRIGGER refuse_job BEFORE UPDATE ON game_analyses
                    FOR EACH ROW WHEN (OLD.job_id = '%s') EXECUTE FUNCTION refuse_job();
                """.formatted(jobId));
        try {
            long sent = System.nanoTime();
            sendResult("result.completed.json", jobId, matchId);

            AtomicReference<@Nullable Message> deadLetter = new AtomicReference<>();
            await("result dead-lettered").atMost(TIMEOUT).until(() -> {
                deadLetter.set(rabbitTemplate.receive("analysis.results.dlq"));
                return deadLetter.get() != null;
            });
            // Three attempts, 1 s and 2 s apart; a fourth would come 4 s later
            assertThat(Duration.ofNanos(System.nanoTime() - sent))
                    .isBetween(Duration.ofSeconds(3), Duration.ofSeconds(6));
            assertThat(jsonMapper
                            .readTree(Objects.requireNonNull(deadLetter.get()).getBody())
                            .get("jobId")
                            .asString())
                    .isEqualTo(jobId);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER refuse_job ON game_analyses; DROP FUNCTION refuse_job();");
        }

        // The database is back: the sweeper fails the job, which can be retried
        JsonNode timedOut = next(updates);
        assertThat(timedOut.get("status").asString()).isEqualTo("FAILED");
        assertThat(timedOut.get("errorCode").asString()).isEqualTo("TIMED_OUT");
    }

    private void sendResult(String fixture, String jobId, UUID matchId) throws IOException {
        ObjectNode result = (ObjectNode) jsonMapper.readTree(Files.readAllBytes(FIXTURES.resolve(fixture)));
        result.put("jobId", jobId);
        result.put("matchId", matchId.toString());
        rabbitTemplate.send("analysis", "result", json(jsonMapper.writeValueAsBytes(result)));
    }

    private void drain(String queue) {
        while (rabbitTemplate.receive(queue) != null) {
            // discard messages left by earlier tests
        }
    }
}
