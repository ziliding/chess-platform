package me.zilid.chessplatform.repository;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An archived match's players and PGN, as {@link MatchRecordRepo#findParticipantsById} returns them. A player's ID and
 * name are null once they have deleted their account.
 */
public record MatchParticipants(
        UUID matchId,
        @Nullable UUID whiteId,
        @Nullable String whiteName,
        @Nullable UUID blackId,
        @Nullable String blackName,
        String pgn) {
    public boolean isPlayer(UUID userId) {
        return userId.equals(whiteId) || userId.equals(blackId);
    }
}
