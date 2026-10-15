package com.dameokja.backend.analysis.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AnalysisCompletionItem(String itemId, RecognitionStatus displayStatus, String name, String category, String storageType,
        String measureType, Integer quantity, Weight weight, Expiration expiration, List<AnalysisReviewReason> reviewReasons) {
    public AnalysisCompletionItem {
        reviewReasons = List.copyOf(reviewReasons);
    }

    public record Weight(BigDecimal value, String unit) {}

    public record Expiration(LocalDate date, String dateType, EstimatedRange estimatedRange) {}

    public record EstimatedRange(@JsonProperty("from") LocalDate from, @JsonProperty("to") LocalDate to) {}
}
