package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationList;
import java.util.Optional;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationListRepository extends JpaRepository<RecommendationList, Long> {
    Optional<RecommendationList> findByRequestId(String requestId);
    Optional<RecommendationList> findFirstByUserIdAndRefrigeratorIdAndRequestDateAndInputKeyOrderByIdDesc(
            Long userId, Long refrigeratorId, LocalDate requestDate, String inputKey);
    Optional<RecommendationList> findByIdAndUserIdAndRefrigeratorId(Long id, Long userId, Long refrigeratorId);
}
