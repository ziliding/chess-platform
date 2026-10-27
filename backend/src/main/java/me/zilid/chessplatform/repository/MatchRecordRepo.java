package me.zilid.chessplatform.repository;

import java.util.Optional;
import java.util.UUID;
import me.zilid.chessplatform.model.entity.MatchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchRecordRepo extends CrudRepository<MatchRecord, UUID> {
    Page<MatchRecord> findByWhitePlayer_IdOrBlackPlayer_Id(UUID whitePlayerId, UUID blackPlayerId, Pageable pageable);

    /** What analysis needs of a match, without loading its players as entities. */
    @Query("""
            select new me.zilid.chessplatform.repository.MatchParticipants(
                m.id, m.whitePlayer.id, m.whitePlayer.username, m.blackPlayer.id, m.blackPlayer.username, m.pgn)
            from MatchRecord m where m.id = :id
            """)
    Optional<MatchParticipants> findParticipantsById(@Param("id") UUID id);
}
