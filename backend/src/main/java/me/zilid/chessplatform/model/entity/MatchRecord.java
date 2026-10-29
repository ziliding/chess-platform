package me.zilid.chessplatform.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import me.zilid.chessplatform.chess.game.TimeControl;
import me.zilid.chessplatform.rating.RatingChange;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "match_records")
public class MatchRecord extends BaseEntity {
    // Null once that player deletes their account; the match stays in the opponent's history.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "white_user_id", updatable = false)
    private @Nullable User whitePlayer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "black_user_id", updatable = false)
    private @Nullable User blackPlayer;

    @Column(name = "result")
    private String matchResult;

    @Column(name = "reason")
    private String reason;

    @Column(name = "pgn")
    private String pgn;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    // Rating snapshot; null for matches archived before ratings were tracked.
    @Enumerated(EnumType.STRING)
    @Column(name = "time_control", updatable = false)
    private @Nullable TimeControl timeControl;

    @Column(name = "white_rating")
    private @Nullable Integer whiteRating;

    @Column(name = "black_rating")
    private @Nullable Integer blackRating;

    @Column(name = "white_rating_change")
    private @Nullable Integer whiteRatingChange;

    @Column(name = "black_rating_change")
    private @Nullable Integer blackRatingChange;

    public MatchRecord(User white, User black, String result, String reason, String pgn, Instant start, Instant end) {
        this.whitePlayer = white;
        this.blackPlayer = black;
        this.matchResult = result;
        this.reason = reason;
        this.pgn = pgn;
        this.startTime = start;
        this.endTime = end;
    }

    protected MatchRecord() {}

    public @Nullable User getWhitePlayer() {
        return whitePlayer;
    }

    public void setWhitePlayer(@Nullable User whitePlayer) {
        this.whitePlayer = whitePlayer;
    }

    public @Nullable User getBlackPlayer() {
        return blackPlayer;
    }

    public void setBlackPlayer(@Nullable User blackPlayer) {
        this.blackPlayer = blackPlayer;
    }

    public String getMatchResult() {
        return matchResult;
    }

    public void setMatchResult(String matchResult) {
        this.matchResult = matchResult;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPgn() {
        return pgn;
    }

    public void setPgn(String pgn) {
        this.pgn = pgn;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public @Nullable TimeControl getTimeControl() {
        return timeControl;
    }

    public void setTimeControl(@Nullable TimeControl timeControl) {
        this.timeControl = timeControl;
    }

    public @Nullable Integer getWhiteRating() {
        return whiteRating;
    }

    public @Nullable Integer getBlackRating() {
        return blackRating;
    }

    public @Nullable Integer getWhiteRatingChange() {
        return whiteRatingChange;
    }

    public @Nullable Integer getBlackRatingChange() {
        return blackRatingChange;
    }

    /** Record the players' ratings after this match and how much each one moved. */
    public void setRatingChange(RatingChange change) {
        this.whiteRating = change.whiteAfter();
        this.blackRating = change.blackAfter();
        this.whiteRatingChange = change.whiteDelta();
        this.blackRatingChange = change.blackDelta();
    }
}
