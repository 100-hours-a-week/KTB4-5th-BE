package com.dameokja.backend.analysis.domain;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AnalysisCompletionItem(String itemId, RecognitionStatus displayStatus, String name, IngredientCategory category, StorageType storageType,
        MeasureType measureType, Integer quantity, Weight weight, Expiration expiration, List<AnalysisReviewReason> reviewReasons) {
    public AnalysisCompletionItem {
        reviewReasons = List.copyOf(reviewReasons);
    }

    public record Weight(BigDecimal value, WeightUnit unit) {}

    public record Expiration(LocalDate date, AnalysisExpirationDateType dateType, EstimatedRange estimatedRange) {}

    public record EstimatedRange(@JsonProperty("from") LocalDate from, @JsonProperty("to") LocalDate to) {}
}
