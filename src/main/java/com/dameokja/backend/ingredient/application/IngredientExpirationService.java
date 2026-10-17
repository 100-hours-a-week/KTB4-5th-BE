package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientStatus;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngredientExpirationService {
    private final IngredientRepository ingredientRepository;

    public int countInWindow(Long refrigeratorId, LocalDate from, LocalDate before) {
        if (!from.isBefore(before)) {
            return 0;
        }
        return Math.toIntExact(ingredientRepository
                .countByRefrigerator_IdAndExpirationDateGreaterThanEqualAndExpirationDateLessThan(refrigeratorId, from, before));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int countInWindowForUpdate(Long refrigeratorId, LocalDate from, LocalDate before) {
        if (!from.isBefore(before)) {
            return 0;
        }
        // 앞선 조회의 스냅샷 대신 냉장고 잠금 획득 후의 최신 행을 집계한다.
        return ingredientRepository.findExpirationIdsForUpdate(refrigeratorId, from, before).size();
    }

    public List<Ingredient> findNotificationTargets(Long refrigeratorId, LocalDate businessDate) {
        LocalDate expirationThrough = businessDate.plusDays(IngredientStatus.EXPIRING_SOON_DAYS);
        return ingredientRepository.findExpirationNotificationTargets(refrigeratorId, expirationThrough);
    }
}
