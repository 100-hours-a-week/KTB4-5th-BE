package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpiredIngredientCountService {
    private final IngredientRepository ingredientRepository;
    private final Clock clock;

    public long count(Long refrigeratorId) {
        LocalDate businessDate = BusinessTime.today(clock);
        return ingredientRepository.countByRefrigerator_IdAndExpirationDateBefore(
                refrigeratorId, businessDate);
    }
}
