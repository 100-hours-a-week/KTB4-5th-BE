package com.dameokja.backend.ingredient.application.update;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.NOT_FOUND;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
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
@Transactional
public class IngredientUpdateService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;
    private final IngredientUpdatePolicy ingredientUpdatePolicy;
    private final Clock clock;

    public IngredientUpdateResult update(Long userId, Long ingredientId, String ifMatch, IngredientUpdateFields fields) {
        LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Ingredient ingredient = findIngredientForUpdate(ingredientId);

        validateUpdateAccess(userId, ingredient);
        validateVersion(ingredient, ifMatch);

        IngredientDetails updateDetails = ingredientUpdatePolicy.createDetails(ingredient, fields, businessDate);
        return updateIngredient(ingredient, updateDetails, businessDate);
    }

    private Ingredient findIngredientForUpdate(Long ingredientId) {
        return ingredientRepository.findByIdForUpdate(ingredientId).orElseThrow(() -> new CustomException(NOT_FOUND));
    }

    private void validateUpdateAccess(Long userId, Ingredient ingredient) {
        Long refrigeratorId = ingredient.getRefrigerator().getId();
        refrigeratorAccessService.validateWriteAccess(userId, refrigeratorId);
    }

    private void validateVersion(Ingredient ingredient, String ifMatch) {
        IngredientEtag.validate(ifMatch);
        IngredientEtag.requireMatch(ingredient, ifMatch);
    }

    private IngredientUpdateResult updateIngredient(Ingredient ingredient, IngredientDetails updateDetails, LocalDate businessDate) {
        ingredient.updateDetails(updateDetails);
        ingredientRepository.flush();
        return new IngredientUpdateResult(ingredient, businessDate);
    }

}
