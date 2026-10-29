package me.zilid.chessplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import me.zilid.chessplatform.chess.game.Game;
import me.zilid.chessplatform.chess.game.RegisteredPlayer;
import me.zilid.chessplatform.chess.game.TestGames;
import me.zilid.chessplatform.config.AnalysisConfig;
import me.zilid.chessplatform.model.dto.AnalysisRequestMessage;
import me.zilid.chessplatform.model.dto.AnalysisResultMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the Java side of the contract in {@code contracts/analysis/v1}, which the Python tests check against the same
 * files. Runs through the AMQP converter built from Boot's JsonMapper, the one the result listener uses.
 */
@SpringBootTest(classes = {JacksonAutoConfiguration.class, AnalysisConfig.class})
class AnalysisContractTest {
    private static final Path FIXTURES = Path.of("..", "contracts", "analysis", "v1");

    @Autowired
    private MessageConverter converter;

    @Autowired
    private JsonMapper jsonMapper;

    static Stream<Path> resultFixtures() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.filter(path -> path.getFileName().toString().startsWith("result.")).sorted().toList().stream();
        }
    }

    static Stream<Path> requestFixtures() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files
                    .filter(path -> path.getFileName().toString().startsWith("request."))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    /** A message as the Python service sends it (no __TypeId__ header) and the listener adapter prepares it. */
    private Message message(Path fixture) throws IOException {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setInferredArgumentType(AnalysisResultMessage.class);
        return new Message(Files.readAllBytes(fixture), properties);
    }

    @ParameterizedTest
    @MethodSource("resultFixtures")
    void everyResultFixtureIsReadThroughTheListenersConverter(Path fixture) throws IOException {
        Object converted = converter.fromMessage(message(fixture));

        assertThat(converted).isInstanceOf(AnalysisResultMessage.class);
        AnalysisResultMessage result = (AnalysisResultMessage) converted;
        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.jobId()).isEqualTo(UUID.fromString("0192c7a4-5b1e-7c3a-9f00-2d1c8e7b6a01"));
        if (result.status().equals("COMPLETED")) {
            assertThat(result.moves()).hasSize(7);
            assertThat(result.moves().get(5).classification()).isEqualTo("BLUNDER");
            assertThat(result.moves().get(6).eval().mate()).isZero();
            boolean llm = fixture.getFileName().toString().contains(".llm.");
            assertThat(Objects.requireNonNull(result.summary()).source()).isEqualTo(llm ? "LLM" : "TEMPLATE");
            assertThat(result.moves().get(5).explanation() != null).isEqualTo(llm);
        } else {
            assertThat(Objects.requireNonNull(result.error()).code()).isEqualTo("INVALID_PGN");
            assertThat(result.moves()).isNull();
        }
    }

    @Test
    void anUnknownFieldDoesNotChangeTheResult() throws IOException {
        Message plain = message(FIXTURES.resolve("result.completed.json"));
        Message extra = message(FIXTURES.resolve("result.completed.extra-field.json"));

        assertThat(converter.fromMessage(extra)).isEqualTo(converter.fromMessage(plain));
    }

    @ParameterizedTest
    @MethodSource("requestFixtures")
    void theRequestSerializesToTheFixture(Path fixture) throws IOException {
        JsonNode expected = jsonMapper.readTree(fixture.toFile());
        AnalysisRequestMessage request = new AnalysisRequestMessage(
                expected.get("schemaVersion").asInt(),
                UUID.fromString(expected.get("jobId").asString()),
                UUID.fromString(expected.get("matchId").asString()),
                expected.get("pgn").asString(),
                Instant.parse(expected.get("deadline").asString()));

        Message message = converter.toMessage(request, new MessageProperties());

        assertThat(jsonMapper.readTree(message.getBody())).isEqualTo(expected);
    }

    /** The fixture holds what the real formatter produces, so a change to the PGN format breaks this test first. */
    @Test
    void platformPgnFixtureIsWhatTheGameFormatterProduces() throws IOException {
        Instant start = Instant.parse("2026-10-26T10:00:00Z");
        Game game = new Game(
                new RegisteredPlayer(UUID.randomUUID(), "alice"),
                new RegisteredPlayer(UUID.randomUUID(), "bob"),
                TestGames.TEN_MINUTES,
                start);
        List<String[]> moves = List.of(
                new String[] {"e2", "e4"},
                new String[] {"e7", "e5"},
                new String[] {"f1", "c4"},
                new String[] {"b8", "c6"},
                new String[] {"d1", "h5"},
                new String[] {"g8", "f6"},
                new String[] {"h5", "f7"});
        for (int i = 0; i < moves.size(); i++) {
            assertThat(game.makeMove(moves.get(i)[0], moves.get(i)[1], null, start.plusSeconds(10L * i)))
                    .as("move " + i)
                    .isTrue();
        }

        String pgn = jsonMapper
                .readTree(FIXTURES.resolve("request.platform-pgn.json").toFile())
                .get("pgn")
                .asString();

        assertThat(game.getNotation()).isEqualTo(pgn);
    }
}
