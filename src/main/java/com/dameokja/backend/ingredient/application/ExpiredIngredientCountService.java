package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpiredIngredientCountService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final IngredientRepository ingredientRepository;
    private final Clock clock;

    public long count(Long refrigeratorId) {
        LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        return ingredientRepository.countByRefrigerator_IdAndExpirationDateBefore(
                refrigeratorId, businessDate);
    }
}
