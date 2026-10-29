package me.zilid.chessplatform.repository.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import me.zilid.chessplatform.chess.game.ClockSetting;
import me.zilid.chessplatform.util.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class MatchmakingQueueIT {
    // An unusual clock keeps this test's queue apart from anything else using Redis
    private static final ClockSetting CLOCK = ClockSetting.parse("7.25+11");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MatchmakingQueue queue;

    @AfterEach
    void clearRedis() {
        redisTemplate.delete(redisTemplate.keys("mm:*"));
    }

    private UUID seeker(int rating) {
        UUID id = UUID.randomUUID();
        queue.heartbeat(id, CLOCK, NOW);
        assertThat(queue.tryMatch(CLOCK, id, rating, 100)).isEmpty();
        return id;
    }

    @Test
    void pairsTheClosestLiveSeekerWithinTheWindow() {
        seeker(1350);
        UUID close = seeker(1220);
        UUID me = UUID.randomUUID();
        queue.heartbeat(me, CLOCK, NOW);

        // Both are in range; the closer rating wins
        assertThat(queue.tryMatch(CLOCK, me, 1200, 200)).contains(close);
        assertThat(queue.takeMatch(close)).isEmpty(); // pending until the game exists

        UUID gameId = UUID.randomUUID();
        queue.offerMatch(close, gameId);
        assertThat(queue.takeMatch(close)).isEqualTo(gameId.toString());
        assertThat(queue.takeMatch(close)).isNull();
    }

    @Test
    void ignoresSeekersOutsideTheWindowAndOneself() {
        seeker(1500);
        UUID me = seeker(1200);

        assertThat(queue.tryMatch(CLOCK, me, 1200, 100)).isEmpty();
        assertThat(queue.tryMatch(CLOCK, me, 1200, 300)).isPresent();
    }

    @Test
    void skipsSeekersThatStoppedPollingOrCancelled() {
        UUID gone = seeker(1200);
        redisTemplate.delete("mm:seek:" + CLOCK + ":" + gone);
        UUID cancelled = seeker(1200);
        queue.cancel(cancelled, CLOCK);
        UUID me = UUID.randomUUID();
        queue.heartbeat(me, CLOCK, NOW);

        assertThat(queue.tryMatch(CLOCK, me, 1200, 100)).isEmpty();
        assertThat(redisTemplate.opsForZSet().range("mm:queue:" + CLOCK, 0, -1)).containsExactly(me.toString());
    }

    @Test
    void heartbeatRemembersWhenTheSeekStarted() {
        UUID me = UUID.randomUUID();

        assertThat(queue.heartbeat(me, CLOCK, NOW)).isEqualTo(NOW);
        assertThat(queue.heartbeat(me, CLOCK, NOW.plusSeconds(3))).isEqualTo(NOW);
    }
}
