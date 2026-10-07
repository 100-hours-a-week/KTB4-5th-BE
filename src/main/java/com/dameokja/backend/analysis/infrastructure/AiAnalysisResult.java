package com.dameokja.backend.analysis.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AiAnalysisResult(DocumentType documentType, ImageQuality imageQuality, List<Item> items, ModelTrace modelTrace) {
    public record DocumentType(String value, double confidence) {}

    public record ImageQuality(double score, List<String> issues) {}

    public record Item(String itemId, String displayStatus, Name name, Category category, String storageType,
            String measureType, Quantity quantity, Weight weight, Expiration expiration, List<String> reviewReasons) {}

    public record Name(String value, String normalizedValue, String source, double confidence, String evidenceText) {}

    public record Category(String value, String source, double confidence) {}

    public record Quantity(int value, String source, double confidence) {}

    public record Weight(BigDecimal value, String unit, String source, double confidence, String evidenceText) {}

    public record Expiration(LocalDate date, String dateType, String source, Double confidence, String evidenceText,
            EstimatedRange estimatedRange, List<String> assumptions, List<String> evidenceIds) {}

    public record EstimatedRange(@JsonProperty("from") LocalDate from, @JsonProperty("to") LocalDate to) {}

    public record ModelTrace(String pipelineVersion, String ocrVersion, String llmModel, String normalizerVersion, String policyVersion) {}
}
