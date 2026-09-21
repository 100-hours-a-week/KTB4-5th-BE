package com.dameokja.backend.ingredient.application.update;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_UPDATE_AMOUNT;
import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_UPDATE_MEASUREMENT;
import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_UPDATE_VALUE;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.IngredientPolicy;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields.UpdateField;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IngredientUpdatePolicy {
    private final IngredientPolicy ingredientPolicy;

    public IngredientDetails createDetails(Ingredient ingredient, IngredientUpdateFields fields, LocalDate businessDate) {
        try {
            IngredientDetails requestedDetails = applyFields(ingredient, fields);
            return ingredientPolicy.validate(requestedDetails, businessDate, ingredient.getExpirationDate());
        } catch (CustomException exception) {
            throw translateException(exception);
        }
    }

    private IngredientDetails applyFields(Ingredient ingredient, IngredientUpdateFields fields) {
        Measurement measurement = createMeasurement(fields, ingredient.getMeasurement());
        return new IngredientDetails(
                requiredValue(fields.name(), ingredient.getName()),
                requiredValue(fields.category(), ingredient.getCategory()),
                requiredValue(fields.storageType(), ingredient.getStorageType()),
                measurement,
                requiredValue(fields.expirationDate(), ingredient.getExpirationDate()));
    }

    private Measurement createMeasurement(IngredientUpdateFields fields, Measurement current) {
        validateWeightUnit(fields.weightUnit(), current.getWeightUnit());
        if (current.getMeasureType() == MeasureType.COUNT) {
            validateUnusedField(fields.weightValue());
            Integer quantity = requiredValue(fields.quantity(), current.getQuantity().intValue());
            return Measurement.of(MeasureType.COUNT, quantity, null, WeightUnit.NONE);
        }
        validateUnusedField(fields.quantity());
        BigDecimal weightValue = requiredValue(fields.weightValue(), current.getWeightValue());
        return Measurement.of(MeasureType.WEIGHT, null, weightValue, current.getWeightUnit());
    }

    private void validateWeightUnit(UpdateField<WeightUnit> field, WeightUnit currentUnit) {
        WeightUnit requestedUnit = requiredValue(field, currentUnit);
        if (requestedUnit != currentUnit) {
            throw new CustomException(IngredientExceptionCode.INVALID_INPUT);
        }
    }

    private void validateUnusedField(UpdateField<?> field) {
        if (field.provided() && field.value() != null) {
            throw new CustomException(IngredientExceptionCode.MIXED_MEASUREMENT);
        }
    }

    private <T> T requiredValue(UpdateField<T> field, T currentValue) {
        if (!field.provided()) {
            return currentValue;
        }
        if (field.value() == null) {
            throw new CustomException(IngredientExceptionCode.INVALID_INPUT);
        }
        return field.value();
    }

    private CustomException translateException(CustomException exception) {
        if (exception.getExceptionCode() == IngredientExceptionCode.INVALID_AMOUNT) {
            return new CustomException(INVALID_UPDATE_AMOUNT);
        }
        if (exception.getExceptionCode() == IngredientExceptionCode.MIXED_MEASUREMENT) {
            return new CustomException(INVALID_UPDATE_MEASUREMENT);
        }
        if (exception.getExceptionCode() == IngredientExceptionCode.INVALID_INPUT) {
            return new CustomException(INVALID_UPDATE_VALUE);
        }
        return exception;
    }
}
