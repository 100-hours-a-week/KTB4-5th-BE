package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationOutboxRepository extends JpaRepository<RecommendationOutbox, Long> {
    Optional<RecommendationOutbox> findByEventId(String eventId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RecommendationOutbox o where o.eventId = :eventId")
    Optional<RecommendationOutbox> lockByEventId(@Param("eventId") String eventId);
    Optional<RecommendationOutbox> findFirstByRequestIdOrderByIdAsc(String requestId);
    Optional<RecommendationOutbox> findFirstByRequestIdAndMessageBodyIsNotNullOrderByIdAsc(String requestId);
    List<RecommendationOutbox> findByIdGreaterThanOrderByIdAsc(Long lastScannedOutboxId, Pageable pageable);
    List<RecommendationOutbox> findByRequestIdOrderByIdAsc(String requestId);

    @Query(value = """
            SELECT * FROM recommendation_outbox WHERE status = 'PENDING' AND next_attempt_at <= CAST(:now AS DATETIME(6))
            ORDER BY next_attempt_at, recommendation_outbox_id LIMIT :size FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RecommendationOutbox> findPendingForUpdate(String now, int size);

    @Query(value = """
            SELECT * FROM recommendation_outbox WHERE status = 'PUBLISHING' AND lease_until <= CAST(:now AS DATETIME(6))
            ORDER BY lease_until, recommendation_outbox_id LIMIT :size FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RecommendationOutbox> findExpiredForUpdate(String now, int size);

    @Modifying
    @Query(value = """
            UPDATE recommendation_outbox SET status = 'SENT', lease_token = NULL, lease_until = NULL, updated_at = CAST(:now AS DATETIME(6))
            WHERE recommendation_outbox_id = :id AND status = 'PUBLISHING' AND lease_token = :token AND lease_until > CAST(:now AS DATETIME(6))
            """, nativeQuery = true)
    int markPublished(Long id, String token, String now);

    @Modifying
    @Query(value = """
            UPDATE recommendation_outbox SET status = 'PENDING', lease_token = NULL, lease_until = NULL,
                next_attempt_at = CAST(:next AS DATETIME(6)), updated_at = CAST(:now AS DATETIME(6))
            WHERE recommendation_outbox_id = :id AND status = 'PUBLISHING' AND lease_token = :token AND lease_until > CAST(:now AS DATETIME(6))
            """, nativeQuery = true)
    int retryLater(Long id, String token, String now, String next);
}
