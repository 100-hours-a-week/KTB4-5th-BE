package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationState;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationStateRepository extends JpaRepository<RecommendationState, Long> {
    Optional<RecommendationState> findByUserIdAndRefrigeratorId(Long userId, Long refrigeratorId);
}
