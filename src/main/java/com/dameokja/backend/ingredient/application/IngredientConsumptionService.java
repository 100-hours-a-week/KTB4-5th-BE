package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
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

    /**
     * 재고를 차감하고 해당 트랜잭션에서 사용한 재고 내역을 반환합니다.
     * @param refrigeratorId 재고가 속한 냉장고 ID
     * @param amounts 재고 ID별 차감 요청량
     * @return 실제 차감에 사용한 재고 ID, 수량 및 단위
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ConsumedIngredient> consume(Long refrigeratorId, Map<Long, BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return List.of();
        }
        List<Ingredient> ingredients = ingredientRepository.findAllByIdsForUpdate(refrigeratorId, amounts.keySet());
        if (ingredients.size() != amounts.size()) { throw new CustomException(IngredientExceptionCode.NOT_FOUND); }
        List<ConsumedIngredient> consumed = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            Measurement consumedAmount = consumedAmount(ingredient, amounts.get(ingredient.getId()));
            if (ingredient.consume(consumedAmount)) {
                ingredientRepository.delete(ingredient);
            }
            consumed.add(ConsumedIngredient.from(ingredient.getId(), consumedAmount));
        }
        ingredientRepository.flush();
        return List.copyOf(consumed);
    }

    private Measurement consumedAmount(Ingredient ingredient, BigDecimal amount) {
        Measurement current = ingredient.getMeasurement();
        if (current.getMeasureType() == MeasureType.COUNT) {
            return Measurement.of(MeasureType.COUNT, amount.intValueExact(), null, current.getWeightUnit());
        }
        return Measurement.of(MeasureType.WEIGHT, null, amount, current.getWeightUnit());
    }

    public record ConsumedIngredient(Long ingredientId, BigDecimal amount, String unit) {
        private static ConsumedIngredient from(Long ingredientId, Measurement measurement) {
            if (measurement.getMeasureType() == MeasureType.COUNT) {
                return new ConsumedIngredient(ingredientId, BigDecimal.valueOf(measurement.getQuantity()), "EA");
            }
            return new ConsumedIngredient(ingredientId, measurement.getWeightValue(), measurement.getWeightUnit().name());
        }
    }
}
