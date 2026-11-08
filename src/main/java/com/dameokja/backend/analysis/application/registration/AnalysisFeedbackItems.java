package com.dameokja.backend.analysis.application.registration;

import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import java.util.Objects;
import java.time.LocalDate;

final class AnalysisFeedbackItems {
    private AnalysisFeedbackItems() {}

    static AiAnalysisFeedbackRequest.Item confirmed(String itemId, AnalysisCompletionItem original, AiAnalysisFeedbackRequest.Confirmed value) {
        if (itemId == null) {
            return new AiAnalysisFeedbackRequest.Item(null, "ADDED", value);
        }
        if (unchanged(original, value)) {
            return new AiAnalysisFeedbackRequest.Item(itemId, "CONFIRMED", null);
        }
        return new AiAnalysisFeedbackRequest.Item(itemId, "CORRECTED", value);
    }

    private static boolean unchanged(AnalysisCompletionItem original, AiAnalysisFeedbackRequest.Confirmed value) {
        LocalDate originalExpirationDate = null;
        if (original.expiration() != null) {
            originalExpirationDate = original.expiration().date();
        }
        return Objects.equals(original.name(), value.name()) && original.category() == value.category()
                && original.storageType() == value.storageType() && original.measureType() == value.measureType()
                && Objects.equals(original.quantity(), value.quantity()) && sameWeight(original.weight(), value)
                && Objects.equals(originalExpirationDate, value.expirationDate());
    }

    private static boolean sameWeight(AnalysisCompletionItem.Weight weight, AiAnalysisFeedbackRequest.Confirmed value) {
        if (weight == null || weight.value() == null) {
            return value.weightValue() == null;
        }
        return value.weightValue() != null && weight.value().compareTo(value.weightValue()) == 0 && weight.unit() == value.weightUnit();
    }
}
