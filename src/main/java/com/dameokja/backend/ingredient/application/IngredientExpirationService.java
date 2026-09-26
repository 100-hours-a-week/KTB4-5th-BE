package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientStatus;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngredientExpirationService {
    private final IngredientRepository ingredientRepository;

    public List<Ingredient> findNotificationTargets(Long refrigeratorId, LocalDate businessDate) {
        LocalDate expirationThrough = businessDate.plusDays(IngredientStatus.EXPIRING_SOON_DAYS);
        return ingredientRepository.findExpirationNotificationTargets(refrigeratorId, expirationThrough);
    }
}
