package me.zilid.chessplatform.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.zilid.chessplatform.chess.game.TimeControl;
import me.zilid.chessplatform.model.entity.Rating;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RatingRepo extends CrudRepository<Rating, UUID> {

    List<Rating> findByUser_Id(UUID userId);

    Optional<Rating> findByUser_IdAndTimeControl(UUID userId, TimeControl timeControl);

    /**
     * Load a rating row and hold a row lock until the transaction ends, so concurrent games for the same player apply
     * their rating changes one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rating r where r.user.id = :userId and r.timeControl = :timeControl")
    Optional<Rating> findForUpdate(@Param("userId") UUID userId, @Param("timeControl") TimeControl timeControl);
}
