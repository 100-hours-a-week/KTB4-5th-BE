package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationList;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationListRepository extends JpaRepository<RecommendationList, Long> {
    Optional<RecommendationList> findByRequestId(String requestId);
    Optional<RecommendationList> findByIdAndUserIdAndRefrigeratorId(Long id, Long userId, Long refrigeratorId);
}
