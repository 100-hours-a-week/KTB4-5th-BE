package com.dameokja.backend.ingredient.application.detail;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.NOT_FOUND;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngredientDetailService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;
    private final Clock clock;

    public IngredientDetailResult getDetail(Long userId, Long ingredientId) {
        LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Ingredient ingredient = ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> new CustomException(NOT_FOUND));
        Long refrigeratorId = ingredient.getRefrigerator().getId();
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);

        return new IngredientDetailResult(ingredient, businessDate);
    }
}
