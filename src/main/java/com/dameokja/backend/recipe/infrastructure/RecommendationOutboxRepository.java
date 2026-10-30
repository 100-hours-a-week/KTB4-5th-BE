package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationOutboxRepository extends JpaRepository<RecommendationOutbox, Long> {
    Optional<RecommendationOutbox> findByEventId(String eventId);
    Optional<RecommendationOutbox> findFirstByRequestIdOrderByIdAsc(String requestId);
    List<RecommendationOutbox> findByIdGreaterThanOrderByIdAsc(Long lastScannedOutboxId, Pageable pageable);
    List<RecommendationOutbox> findByRequestIdOrderByIdAsc(String requestId);
}
