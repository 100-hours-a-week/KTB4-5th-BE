package com.dameokja.backend.analysis.infrastructure.ai;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record AiAnalysisFeedbackRequest(Long refrigeratorId, boolean consentForModelImprovement, List<Item> items) {
    public AiAnalysisFeedbackRequest {
        items = List.copyOf(items);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String itemId, String action, Confirmed confirmed) {
        public static Item corrected(String itemId, Confirmed confirmed) {
            return new Item(itemId, "CORRECTED", confirmed);
        }
    }

    public record Confirmed(String name, IngredientCategory category, StorageType storageType, MeasureType measureType, Integer quantity,
            BigDecimal weightValue, WeightUnit weightUnit, LocalDate expirationDate, String expirationSource) {
        public static Confirmed userConfirmed(String name, IngredientCategory category, StorageType storageType, MeasureType measureType,
                Integer quantity, BigDecimal weightValue, WeightUnit weightUnit, LocalDate expirationDate) {
            return new Confirmed(name, category, storageType, measureType, quantity, weightValue, weightUnit, expirationDate, "USER_CONFIRMED");
        }
    }
}
