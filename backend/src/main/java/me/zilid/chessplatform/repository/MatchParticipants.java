package me.zilid.chessplatform.repository;

import java.util.UUID;

/** An archived match's players and PGN, as {@link MatchRecordRepo#findParticipantsById} returns them. */
public record MatchParticipants(
        UUID matchId, UUID whiteId, String whiteName, UUID blackId, String blackName, String pgn) {
    public boolean isPlayer(UUID userId) {
        return userId.equals(whiteId) || userId.equals(blackId);
    }
}
