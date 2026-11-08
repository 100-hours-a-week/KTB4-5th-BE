package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngredientConsumptionService {
    private final IngredientRepository ingredientRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Long refrigeratorId, Map<Long, BigDecimal> amounts) {
        if (amounts.isEmpty()) { return; }
        List<Ingredient> ingredients = ingredientRepository.findAllByIdsForUpdate(refrigeratorId, amounts.keySet());
        if (ingredients.size() != amounts.size()) { throw new CustomException(IngredientExceptionCode.NOT_FOUND); }
        for (Ingredient ingredient : ingredients) {
            Measurement consumedAmount = consumedAmount(ingredient, amounts.get(ingredient.getId()));
            if (ingredient.consume(consumedAmount)) { ingredientRepository.delete(ingredient); }
        }
        ingredientRepository.flush();
    }

    private Measurement consumedAmount(Ingredient ingredient, BigDecimal amount) {
        Measurement current = ingredient.getMeasurement();
        if (current.getMeasureType() == MeasureType.COUNT) {
            return Measurement.of(MeasureType.COUNT, amount.intValueExact(), null, current.getWeightUnit());
        }
        return Measurement.of(MeasureType.WEIGHT, null, amount, current.getWeightUnit());
    }
}
