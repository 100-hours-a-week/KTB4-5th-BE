package com.dameokja.backend.analysis.infrastructure.ai;

import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import com.dameokja.backend.analysis.domain.AnalysisReviewReason;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AiImageAnalysisResult(DocumentType documentType, ImageQuality imageQuality, List<Item> items, ModelTrace modelTrace) {
    private static final String BUSINESS_RULE = "BUSINESS_RULE";

    public record DocumentType(String value, double confidence) {}

    public record ImageQuality(double score, List<String> issues) {}

    public record Item(String itemId, String displayStatus, Name name, Category category, String storageType,
            String measureType, Quantity quantity, Weight weight, Expiration expiration, List<AnalysisReviewReason> reviewReasons) {
        public Item {
            reviewReasons = List.copyOf(reviewReasons);
        }

        // AI 콜백 변환(app/adapters/callback.py _callback_item)과 같은 규칙이다. 규칙이 바뀌면 함께 고친다.
        AnalysisCompletionItem toCompletionItem() {
            boolean count = MeasureType.COUNT.name().equals(measureType);
            Integer completionQuantity = count && quantity != null && !BUSINESS_RULE.equals(quantity.source()) ? quantity.value() : null;
            AnalysisCompletionItem.Weight completionWeight = count ? new AnalysisCompletionItem.Weight(null, WeightUnit.NONE) : recognizedWeight();
            boolean measurementMissing = count ? completionQuantity == null : completionWeight.value() == null;
            RecognitionStatus status = RecognitionStatus.valueOf(displayStatus);
            if (measurementMissing && (status == RecognitionStatus.RECOGNIZED || status == RecognitionStatus.AI_ESTIMATED)) {
                status = RecognitionStatus.NEEDS_REVIEW;
            }
            return new AnalysisCompletionItem(itemId, status, name.displayValue(), IngredientCategory.valueOf(category.value()),
                    StorageType.valueOf(storageType), MeasureType.valueOf(measureType), completionQuantity, completionWeight,
                    expiration.toCompletionExpiration(), reviewReasons);
        }

        private AnalysisCompletionItem.Weight recognizedWeight() {
            if (weight.value() == null || weight.value().signum() <= 0) {
                return new AnalysisCompletionItem.Weight(null, null);
            }
            return new AnalysisCompletionItem.Weight(weight.value(), WeightUnit.valueOf(weight.unit()));
        }
    }

    public record Name(String value, String normalizedValue, String source, double confidence, String evidenceText) {
        private String displayValue() {
            if (value != null && !value.isEmpty()) {
                return value;
            }
            return normalizedValue != null && !normalizedValue.isEmpty() ? normalizedValue : null;
        }
    }

    public record Category(String value, String source, double confidence) {}

    public record Quantity(int value, String source, double confidence) {}

    public record Weight(BigDecimal value, String unit, String source, double confidence, String evidenceText) {}

    public record Expiration(LocalDate date, String dateType, String source, Double confidence, String evidenceText,
            EstimatedRange estimatedRange, List<String> assumptions, List<String> evidenceIds) {
        private AnalysisCompletionItem.Expiration toCompletionExpiration() {
            AnalysisCompletionItem.EstimatedRange range = estimatedRange == null ? null
                    : new AnalysisCompletionItem.EstimatedRange(estimatedRange.from(), estimatedRange.to());
            return new AnalysisCompletionItem.Expiration(date, AnalysisExpirationDateType.valueOf(dateType), range);
        }
    }

    public record EstimatedRange(@JsonProperty("from") LocalDate from, @JsonProperty("to") LocalDate to) {}

    public record ModelTrace(String pipelineVersion, String ocrVersion, String llmModel, String normalizerVersion, String policyVersion) {}

    AnalysisCompletionSuccess toCompletionResult() {
        return new AnalysisCompletionSuccess(AnalysisDocumentType.valueOf(documentType.value()),
                items.stream().map(Item::toCompletionItem).toList());
    }
}
