package me.zilid.chessplatform.repository.game;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.zilid.chessplatform.chess.game.ClockSetting;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Players looking for an opponent, kept in Redis so every backend instance shares one queue per clock setting.
 *
 * <p>A queue is a sorted set of user ids scored by rating. Each waiting player also has a seek key that their polls
 * keep alive; once a player stops polling, the key expires and the queue skips and drops them.
 */
@Component
public class MatchmakingQueue {

    static final Duration SEEK_TTL = Duration.ofSeconds(10);
    static final Duration MATCH_TTL = Duration.ofSeconds(60);
    // Stored in a player's match key while the game that pairs them is being created
    private static final String PENDING = "";

    // Pair the caller with the closest-rated live seeker in the window, or enqueue the caller. Atomic, so two
    // instances can never take the same opponent.
    // ponytail: builds key names inside the script; fine on a single Redis, Redis Cluster would need hash tags.
    private static final RedisScript<String> TRY_MATCH = RedisScript.of("""
            local me, rating, window = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])
            local seekPrefix, matchPrefix, matchTtl = ARGV[4], ARGV[5], ARGV[6]
            local candidates = redis.call('zrangebyscore', KEYS[1], rating - window, rating + window, 'withscores')
            local best, bestDiff
            for i = 1, #candidates, 2 do
                local id = candidates[i]
                if id ~= me then
                    if redis.call('exists', seekPrefix .. id) == 0 then
                        redis.call('zrem', KEYS[1], id)
                    else
                        local diff = math.abs(tonumber(candidates[i + 1]) - rating)
                        if best == nil or diff < bestDiff then
                            best, bestDiff = id, diff
                        end
                    end
                end
            end
            if best then
                redis.call('zrem', KEYS[1], me, best)
                redis.call('del', seekPrefix .. me, seekPrefix .. best)
                redis.call('set', matchPrefix .. best, '', 'EX', matchTtl)
                return best
            end
            redis.call('zadd', KEYS[1], rating, me)
            return false
            """, String.class);

    private final StringRedisTemplate redisTemplate;

    MatchmakingQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Keep the player's seek alive for another {@link #SEEK_TTL}.
     *
     * @return when the player started this seek
     */
    public Instant heartbeat(UUID userId, ClockSetting clock, Instant now) {
        String key = seekKey(clock, userId);
        var ops = redisTemplate.opsForValue();
        if (Boolean.TRUE.equals(ops.setIfAbsent(key, String.valueOf(now.toEpochMilli()), SEEK_TTL))) {
            return now;
        }
        String since = ops.getAndExpire(key, SEEK_TTL);
        if (since == null) {
            // Expired between the two calls
            ops.set(key, String.valueOf(now.toEpochMilli()), SEEK_TTL);
            return now;
        }
        return Instant.ofEpochMilli(Long.parseLong(since));
    }

    /**
     * Pair the player with the closest-rated live seeker within {@code window} rating points, or add them to the queue.
     * A paired opponent's match is marked pending until {@link #offerMatch} or {@link #clearMatch}.
     */
    public Optional<UUID> tryMatch(ClockSetting clock, UUID userId, int rating, int window) {
        String opponent = redisTemplate.execute(
                TRY_MATCH,
                List.of(queueKey(clock)),
                userId.toString(),
                String.valueOf(rating),
                String.valueOf(window),
                seekKey(clock, ""),
                matchKey(""),
                String.valueOf(MATCH_TTL.toSeconds()));
        return Optional.ofNullable(opponent).map(UUID::fromString);
    }

    public void offerMatch(UUID userId, UUID gameId) {
        redisTemplate.opsForValue().set(matchKey(userId), gameId.toString(), MATCH_TTL);
    }

    public void clearMatch(UUID userId) {
        redisTemplate.delete(matchKey(userId));
    }

    /**
     * @return the game another player paired this player into, consuming it; {@link #PENDING} while that game is still
     *     being created; null if nobody has paired this player
     */
    public @Nullable String takeMatch(UUID userId) {
        String key = matchKey(userId);
        String match = redisTemplate.opsForValue().get(key);
        if (match != null && !match.equals(PENDING)) {
            redisTemplate.delete(key);
        }
        return match;
    }

    public void cancel(UUID userId, ClockSetting clock) {
        redisTemplate.delete(seekKey(clock, userId));
        redisTemplate.opsForZSet().remove(queueKey(clock), userId.toString());
    }

    private static String queueKey(ClockSetting clock) {
        return "mm:queue:" + clock;
    }

    private static String seekKey(ClockSetting clock, Object userId) {
        return "mm:seek:" + clock + ":" + userId;
    }

    private static String matchKey(Object userId) {
        return "mm:match:" + userId;
    }
}
