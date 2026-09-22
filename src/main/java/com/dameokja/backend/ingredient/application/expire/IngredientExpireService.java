package com.dameokja.backend.ingredient.application.expire;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.NOT_FOUND;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class IngredientExpireService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;

    public Optional<IngredientExpireResult> expire(Long userId, Long ingredientId, String ifMatch,
            Integer quantity, BigDecimal weightValue) {
        Refrigerator refrigerator = refrigeratorAccessService.lockCurrentForWrite(userId);
        Optional<Ingredient> foundIngredient = ingredientRepository.findByIdForUpdate(ingredientId);
        if (foundIngredient.isEmpty()) {
            return Optional.empty();
        }

        Ingredient ingredient = foundIngredient.orElseThrow();
        validateAccessAndVersion(refrigerator, ingredient, ifMatch);
        Measurement expiredAmount = createExpiredAmount(ingredient, quantity, weightValue);
        boolean removed = expireIngredient(ingredient, expiredAmount);

        persistExpiration(refrigerator, ingredient, removed);
        return Optional.of(new IngredientExpireResult(ingredient, removed));
    }

    private void validateAccessAndVersion(Refrigerator refrigerator, Ingredient ingredient, String ifMatch) {
        if (!ingredient.getRefrigerator().getId().equals(refrigerator.getId())) {
            throw new CustomException(NOT_FOUND);
        }
        IngredientEtag.validate(ifMatch);
        IngredientEtag.requireMatch(ingredient, ifMatch);
    }

    private Measurement createExpiredAmount(Ingredient ingredient, Integer quantity, BigDecimal weightValue) {
        Measurement current = ingredient.getMeasurement();
        try {
            return Measurement.of(current.getMeasureType(), quantity, weightValue, current.getWeightUnit());
        } catch (CustomException exception) {
            throw translateMeasurementException(exception);
        }
    }

    private CustomException translateMeasurementException(CustomException exception) {
        if (exception.getExceptionCode() == IngredientExceptionCode.INVALID_AMOUNT) {
            return new CustomException(IngredientExceptionCode.INVALID_EXPIRE_AMOUNT);
        }
        if (exception.getExceptionCode() == IngredientExceptionCode.MIXED_MEASUREMENT) {
            return new CustomException(IngredientExceptionCode.INVALID_EXPIRE_MEASUREMENT);
        }
        return exception;
    }

    private boolean expireIngredient(Ingredient ingredient, Measurement expiredAmount) {
        try {
            return ingredient.expire(expiredAmount);
        } catch (CustomException exception) {
            throw translateMeasurementException(exception);
        }
    }

    private void persistExpiration(Refrigerator refrigerator, Ingredient ingredient, boolean removed) {
        if (removed) {
            ingredientRepository.delete(ingredient);
            refrigerator.countExpiredDeletion();
        }
        ingredientRepository.flush();
    }
}
