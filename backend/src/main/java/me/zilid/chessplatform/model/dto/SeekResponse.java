package me.zilid.chessplatform.model.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** @param gameId the game the player was paired into, when {@code status} is MATCHED */
public record SeekResponse(Status status, @Nullable UUID gameId) {
    public static final SeekResponse SEARCHING = new SeekResponse(Status.SEARCHING, null);

    public static SeekResponse matched(UUID gameId) {
        return new SeekResponse(Status.MATCHED, gameId);
    }

    public enum Status {
        SEARCHING,
        MATCHED,
    }
}
