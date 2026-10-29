package me.zilid.chessplatform.model.converter;

import me.zilid.chessplatform.model.dto.MatchRecordResponse;
import me.zilid.chessplatform.model.dto.UserResponse;
import me.zilid.chessplatform.model.entity.MatchRecord;
import me.zilid.chessplatform.model.entity.User;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class MatchRecordConverter {
    private final UserConverter userConverter;

    public MatchRecordConverter(UserConverter userConverter) {
        this.userConverter = userConverter;
    }

    public MatchRecordResponse toResponse(MatchRecord matchRecord) {
        return new MatchRecordResponse(
                matchRecord.getId(),
                toResponse(matchRecord.getWhitePlayer()),
                toResponse(matchRecord.getBlackPlayer()),
                matchRecord.getMatchResult(),
                matchRecord.getReason(),
                matchRecord.getStartTime(),
                matchRecord.getEndTime(),
                matchRecord.getTimeControl(),
                matchRecord.getWhiteRating(),
                matchRecord.getBlackRating(),
                matchRecord.getWhiteRatingChange(),
                matchRecord.getBlackRatingChange());
    }

    private @Nullable UserResponse toResponse(@Nullable User player) {
        return player == null ? null : userConverter.toResponse(player);
    }
}
