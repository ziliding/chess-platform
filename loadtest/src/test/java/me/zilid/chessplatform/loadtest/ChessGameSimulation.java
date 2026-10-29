package me.zilid.chessplatform.loadtest;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.WsFrameCheck;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pairs of new players log in and play complete games against each other through the gateway, over REST and STOMP
 * WebSockets the way the frontend does. Each finished game is rated, archived, and read back. Configured with the
 * LOADTEST_* environment variables described in the README.
 *
 * <p>ponytail: one virtual user plays both seats of a game over two sockets, so no cross-user synchronization is
 * needed. nginx still routes each socket on its own, so moves cross backend instances through RabbitMQ.
 */
public class ChessGameSimulation extends Simulation {

    /**
     * Every test type has the same shape: pairs of players arrive evenly over {@code ramp}, and each pair plays a game
     * and keeps starting new ones until {@code duration} after it arrived. The types differ in their defaults and in
     * what counts as a pass.
     */
    private record TestType(int users, String ramp, String duration) {}

    private static final String TYPE = env("LOADTEST_TYPE", "smoke");
    private static final TestType DEFAULTS = switch (TYPE) {
        // One game, to check the script and the stack work at all
        case "smoke" -> new TestType(2, "0s", "0s");
        // Typical load: ramp up, hold, ramp down as pairs leave
        case "average" -> new TestType(100, "30s", "5m");
        // Well above typical load, held as long
        case "stress" -> new TestType(300, "1m", "5m");
        // Everyone arrives within seconds, as at a tournament start, plays one game, and leaves
        case "spike" -> new TestType(400, "10s", "0s");
        // Players keep arriving until a game fails or a socket reply takes over a second; the test then stops and
        // reports how many were playing
        case "breakpoint" -> new TestType(4000, "10m", "10m");
        // Typical load held for an hour: leaks and growth in Redis, RabbitMQ, and PostgreSQL
        case "soak" -> new TestType(100, "1m", "1h");
        default ->
            throw new IllegalArgumentException(
                    "LOADTEST_TYPE must be smoke, average, stress, spike, breakpoint or soak, not " + TYPE);
    };

    private static final String TARGET_URL = env("LOADTEST_TARGET_URL", "http://localhost:3000");
    private static final int USERS = Integer.parseInt(env("LOADTEST_USERS", String.valueOf(DEFAULTS.users())));
    private static final Duration RAMP = duration("LOADTEST_RAMP", DEFAULTS.ramp());
    private static final Duration DURATION = duration("LOADTEST_DURATION", DEFAULTS.duration());
    private static final Duration THINK_TIME = duration("LOADTEST_THINK_TIME", "1s");
    // Unset: no analysis. `completed` or `unavailable`: what requesting the archived game's analysis must give
    private static final String EXPECT_ANALYSIS = env("LOADTEST_EXPECT_ANALYSIS", "none");
    private static final Duration ANALYSIS_TIMEOUT = Duration.ofSeconds(60);

    // Pairs between arrival and departure, for the breakpoint report
    private static final AtomicInteger activePairs = new AtomicInteger();

    // How long a socket reply, such as a move's broadcast, may take before it counts as failed. A breakpoint test
    // fails at the latency limit instead: a 10 second timeout let it reach 4000 players with moves taking 5 seconds.
    private static final Duration TIMEOUT = Duration.ofSeconds(TYPE.equals("breakpoint") ? 1 : 10);
    private static final String PASSWORD = "loadtest-password";

    // The Opera Game, Morphy vs Duke of Brunswick and Count Isouard, 1858: White mates on move 17.
    // No from-to pair repeats, so each move's broadcast is recognized by its last move alone.
    private static final List<String> MOVES = List.of(
            "e2e4", "e7e5", "g1f3", "d7d6", "d2d4", "c8g4", "d4e5", "g4f3", "d1f3", "d6e5", "f1c4", "g8f6", "f3b3",
            "d8e7", "b1c3", "c7c6", "c1g5", "b7b5", "c3b5", "c6b5", "c4b5", "b8d7", "e1c1", "a8d8", "d1d7", "d8d7",
            "h1d1", "e7e6", "b5d7", "f6d7", "b3b8", "d7b8", "d1d8");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** A duration written like 0.5s, 30s, 2m or 1m30s. */
    private static Duration duration(String name, String fallback) {
        String text = env(name, fallback);
        try {
            return Duration.parse("PT" + text.toUpperCase());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must look like 0.5s, 30s, 2m or 1m30s, not " + text, e);
        }
    }

    /** A STOMP frame: the command, one header per line, a blank line, the body, and a NUL terminator. */
    private static String frame(String command, String body, String... headers) {
        StringBuilder frame = new StringBuilder(command).append('\n');
        for (String header : headers) {
            frame.append(header).append('\n');
        }
        return frame.append('\n').append(body).append('\0').toString();
    }

    /** The session cookie of the player in {@code seat}, white or black. */
    private static String sessionCookie(String seat) {
        return "JSESSIONID=#{" + seat + "Session}";
    }

    private static ChainBuilder registerAndLogin(String seat) {
        return exec(session -> session.set(
                        seat + "Name", "lt" + UUID.randomUUID().toString().substring(0, 12)))
                .exec(http("Register")
                        .post("/api/users")
                        .body(StringBody("{\"username\":\"#{" + seat + "Name}\",\"email\":\"#{" + seat
                                + "Name}@loadtest.invalid\",\"rawPassword\":\"" + PASSWORD + "\"}"))
                        .asJson()
                        .check(status().is(201), jmesPath("id").saveAs(seat + "Id")))
                // Login reuses the session in the request, so start without one; each seat then sends its own cookie
                .exec(flushCookieJar())
                .exec(http("Login")
                        .post("/api/login")
                        .body(StringBody("{\"username\":\"#{" + seat + "Name}\",\"password\":\"" + PASSWORD + "\"}"))
                        .asJson()
                        .check(status().is(200)))
                .exec(getCookieValue(CookieKey("JSESSIONID").saveAs(seat + "Session")))
                .exec(flushCookieJar());
    }

    private static WsFrameCheck receipt(String id) {
        return ws.checkTextMessage("Receipt " + id)
                .matching(regex("^RECEIPT\n"))
                .check(regex("receipt-id:" + id));
    }

    /** Connect a seat's socket and subscribe to the game and its chat, like the frontend's game page. */
    private static ChainBuilder connect(String seat) {
        return exec(
                ws("Connect", seat).connect("/ws").header("Cookie", sessionCookie(seat)),
                ws("STOMP connect", seat)
                        .sendText(frame("CONNECT", "", "accept-version:1.2", "host:localhost", "heart-beat:0,0"))
                        .await(TIMEOUT)
                        .on(ws.checkTextMessage("Connected").check(regex("^CONNECTED\n"))),
                // Receipts confirm the broker has the subscriptions before any move is broadcast
                ws("Subscribe game", seat)
                        .sendText(
                                frame("SUBSCRIBE", "", "id:game", "destination:/topic/game.#{gameId}", "receipt:game"))
                        .await(TIMEOUT)
                        .on(receipt("game")),
                ws("Subscribe chat", seat)
                        .sendText(frame(
                                "SUBSCRIBE", "", "id:chat", "destination:/topic/game.#{gameId}.chat", "receipt:chat"))
                        .await(TIMEOUT)
                        .on(receipt("chat")),
                ws("Join", seat).sendText(frame("SEND", "{}", "destination:/app/game/#{gameId}/join")));
    }

    /** Each move waits for its own game update; the mating move waits until the game is archived and rated. */
    private static ChainBuilder playGame() {
        List<ChainBuilder> plies = new ArrayList<>();
        for (int i = 0; i < MOVES.size(); i++) {
            String seat = i % 2 == 0 ? "white" : "black";
            String from = MOVES.get(i).substring(0, 2);
            String to = MOVES.get(i).substring(2);
            boolean mate = i == MOVES.size() - 1;
            WsFrameCheck applied = mate
                    ? ws.checkTextMessage("Game rated").matching(regex(" ratings: "))
                    : ws.checkTextMessage("Move applied")
                            .matching(regex("\"lastMoveFrom\":\"" + from + "\",\"lastMoveTo\":\"" + to + "\""));
            plies.add(pause(THINK_TIME)
                    .exec(ws(mate ? "Mating move" : "Move", seat)
                            .sendText(frame(
                                    "SEND",
                                    "{\"gameId\":\"#{gameId}\",\"moveFrom\":\"" + from + "\",\"moveTo\":\"" + to
                                            + "\"}",
                                    "destination:/app/game/#{gameId}/move",
                                    "content-type:application/json"))
                            .await(TIMEOUT)
                            .on(applied)));
        }
        return exec(plies);
    }

    /** Request an analysis of the game just archived and check the answer LOADTEST_EXPECT_ANALYSIS names. */
    private static ChainBuilder analysis() {
        return switch (EXPECT_ANALYSIS) {
            case "none" -> exec(session -> session);
            case "completed" ->
                exec(http("Request analysis")
                                .post("/api/matches/#{matchId}/analysis")
                                .header("Cookie", sessionCookie("white"))
                                .check(status().in(200, 202), jmesPath("status").saveAs("analysisStatus")))
                        .asLongAsDuring(
                                session -> session.getString("analysisStatus").equals("QUEUED"), ANALYSIS_TIMEOUT)
                        .on(
                                pause(Duration.ofSeconds(1)),
                                exec(http("Get analysis")
                                        .get("/api/matches/#{matchId}/analysis")
                                        .header("Cookie", sessionCookie("white"))
                                        .check(
                                                status().is(200),
                                                jmesPath("status").saveAs("analysisStatus"))))
                        // A failed or still queued analysis fails this request, so the run fails
                        .exec(http("Get completed analysis")
                                .get("/api/matches/#{matchId}/analysis")
                                .header("Cookie", sessionCookie("white"))
                                .check(status().is(200), jmesPath("status").is("COMPLETED")));
            case "unavailable" ->
                exec(http("Request analysis while unavailable")
                        .post("/api/matches/#{matchId}/analysis")
                        .header("Cookie", sessionCookie("white"))
                        .check(status().is(503), jmesPath("code").is("ANALYSIS_UNAVAILABLE")));
            default ->
                throw new IllegalArgumentException(
                        "LOADTEST_EXPECT_ANALYSIS must be completed or unavailable, not " + EXPECT_ANALYSIS);
        };
    }

    private static final ChainBuilder game = exec(
                    http("Create game")
                            .post("/api/games")
                            .queryParam("color", "WHITE")
                            .queryParam("timeControl", "5+3")
                            .header("Cookie", sessionCookie("white"))
                            .check(status().is(201), jmesPath("gameId").saveAs("gameId")),
                    http("Join game")
                            .post("/api/games/#{gameId}/join")
                            .header("Cookie", sessionCookie("white"))
                            .check(status().is(200), jmesPath("role").is("WHITE")),
                    http("Join game")
                            .post("/api/games/#{gameId}/join")
                            .header("Cookie", sessionCookie("black"))
                            .check(status().is(200), jmesPath("role").is("BLACK")))
            .exec(connect("white"), connect("black"))
            .exec(playGame())
            .exec(ws("Close", "white").close(), ws("Close", "black").close())
            // The screens a player opens after a game: ratings, then history and the newest game's PGN. Archived
            // games have their own ID, so the PGN is fetched by the history entry's ID, as the frontend does.
            .exec(
                    http("Get ratings")
                            .get("/api/users/#{whiteId}/ratings")
                            .header("Cookie", sessionCookie("white"))
                            .check(status().is(200)),
                    http("Get game history")
                            .get("/api/games/users/#{whiteId}")
                            .header("Cookie", sessionCookie("white"))
                            .check(status().is(200), jmesPath("content[0].id").saveAs("matchId")),
                    http("Get PGN")
                            .get("/api/matches/#{matchId}/pgn")
                            .header("Cookie", sessionCookie("white"))
                            .check(status().is(200), substring("Rd8#")))
            .exec(analysis());

    // After a failure the pair stops: its sockets may still be open, and the rest of the game would only fail too
    private static final ScenarioBuilder players = scenario("Players")
            .exec(session -> {
                activePairs.incrementAndGet();
                return session;
            })
            .exitBlockOnFail()
            .on(
                    registerAndLogin("white"),
                    registerAndLogin("black"),
                    doWhileDuring(session -> true, DURATION).on(game))
            .exec(stopLoadGeneratorIf(
                    session -> "Breakpoint: a game failed with " + activePairs.get() * 2 + " players active",
                    session -> TYPE.equals("breakpoint") && session.isFailed()))
            .exec(session -> {
                activePairs.decrementAndGet();
                return session;
            });

    private static final HttpProtocolBuilder httpProtocol = http.baseUrl(TARGET_URL)
            .wsBaseUrl(TARGET_URL.replaceFirst("^http", "ws"))
            .acceptHeader("application/json")
            // Gatling would send the target as Origin, which the backend's CORS rejects unless it is an allowed
            // origin; without one, requests are treated like any non-browser client's
            .disableAutoOrigin();

    {
        setUp(players.injectOpen(rampUsers(Math.max(1, USERS / 2)).during(RAMP)))
                .protocols(httpProtocol)
                .assertions(
                        switch (TYPE) {
                            case "smoke" ->
                                List.of(global().failedRequests().count().is(0L));
                            // Finding the failure point is the result, not a failed run
                            case "breakpoint" -> List.of();
                            default ->
                                List.of(
                                        global().failedRequests().percent().lt(1.0),
                                        global().responseTime().percentile(95.0).lt(1000));
                        });
    }
}
