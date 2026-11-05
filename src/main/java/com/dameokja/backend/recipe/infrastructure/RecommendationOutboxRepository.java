package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
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
    List<RecommendationOutbox> findByIdGreaterThanOrderByIdAsc(Long lastScannedOutboxId, Pageable pageable);
    List<RecommendationOutbox> findByRequestIdOrderByIdAsc(String requestId);
}
