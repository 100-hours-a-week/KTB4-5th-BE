package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RecommendationStateRepository extends JpaRepository<RecommendationState, Long> {
    Optional<RecommendationState> findByUserIdAndRefrigeratorId(Long userId, Long refrigeratorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from RecommendationState state where state.user.id = :userId and state.refrigerator.id = :refrigeratorId")
    Optional<RecommendationState> findByParticipantForUpdate(Long userId, Long refrigeratorId);
}
