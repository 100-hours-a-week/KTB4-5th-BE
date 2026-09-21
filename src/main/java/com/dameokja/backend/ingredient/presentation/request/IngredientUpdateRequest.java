package com.dameokja.backend.ingredient.presentation.request;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_INPUT;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields.UpdateField;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.time.LocalDate;

public class IngredientUpdateRequest {
    private UpdateField<String> name = UpdateField.omitted();
    private UpdateField<IngredientCategory> category = UpdateField.omitted();
    private UpdateField<StorageType> storageType = UpdateField.omitted();
    private UpdateField<Integer> quantity = UpdateField.omitted();
    private UpdateField<BigDecimal> weightValue = UpdateField.omitted();
    private UpdateField<WeightUnit> weightUnit = UpdateField.omitted();
    private UpdateField<LocalDate> expirationDate = UpdateField.omitted();
    private boolean containsUnsupportedField;

    @JsonSetter("name")
    public void setName(String value) {
        name = UpdateField.provided(value);
    }

    @JsonSetter("category")
    public void setCategory(IngredientCategory value) {
        category = UpdateField.provided(value);
    }

    @JsonSetter("storageType")
    public void setStorageType(StorageType value) {
        storageType = UpdateField.provided(value);
    }

    @JsonSetter("quantity")
    public void setQuantity(Integer value) {
        quantity = UpdateField.provided(value);
    }

    @JsonSetter("weightValue")
    public void setWeightValue(BigDecimal value) {
        weightValue = UpdateField.provided(value);
    }

    @JsonSetter("weightUnit")
    public void setWeightUnit(WeightUnit value) {
        weightUnit = UpdateField.provided(value);
    }

    @JsonSetter("expirationDate")
    public void setExpirationDate(LocalDate value) {
        expirationDate = UpdateField.provided(value);
    }

    @JsonAnySetter
    public void markUnsupportedField(String fieldName, Object value) {
        containsUnsupportedField = true;
    }

    public IngredientUpdateFields toFields() {
        if (containsUnsupportedField) {
            throw new CustomException(INVALID_INPUT);
        }
        return new IngredientUpdateFields(name, category, storageType, quantity, weightValue, weightUnit, expirationDate);
    }
}
