package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResult;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AnalysisImageResult(String imageObjectKey, AnalysisStatus status, RecognitionStatus recognitionStatus, String documentType,
        List<Item> items, Error error) {
    public static AnalysisImageResult from(String imageObjectKey, AnalysisStatus analysisStatus, AiImageAnalysisResult aiImageAnalysisResult, AiImageAnalysisResponse.Error error) {
        if (aiImageAnalysisResult == null) {
            return new AnalysisImageResult(imageObjectKey, analysisStatus, null, null, List.of(), Error.from(error));
        }
        String documentType = null;
        if (aiImageAnalysisResult.documentType() != null) {
            documentType = aiImageAnalysisResult.documentType().value();
        }
        List<Item> items = aiImageAnalysisResult.items().stream().map(Item::from).toList();
        List<RecognitionStatus> recognitionStatuses = items.stream().map(Item::displayStatus).toList();
        RecognitionStatus recognitionStatus = List.of(RecognitionStatus.UNRECOGNIZED, RecognitionStatus.NEEDS_REVIEW,
                RecognitionStatus.AI_ESTIMATED, RecognitionStatus.RECOGNIZED).stream()
                .filter(recognitionStatuses::contains).findFirst().orElse(RecognitionStatus.UNRECOGNIZED);
        return new AnalysisImageResult(imageObjectKey, analysisStatus, recognitionStatus, documentType, items, Error.from(error));
    }

    public record Item(String itemId, RecognitionStatus displayStatus, String name, String category, String storageType, String measureType,
            Integer quantity, Weight weight, LocalDate expiration, List<String> reviewReasons) {
        public static Item from(AiImageAnalysisResult.Item item) {
            if (item.displayStatus() == null) {
                throw new IllegalArgumentException("AI 항목 표시 상태가 없습니다.");
            }
            RecognitionStatus recognitionStatus = RecognitionStatus.valueOf(item.displayStatus());
            String name = item.name().value();
            String category = item.category().value();
            Integer quantity = null;
            if (item.quantity() != null) {
                quantity = item.quantity().value();
            }
            Weight weight = null;
            if (item.weight() != null) {
                weight = new Weight(item.weight().value(), item.weight().unit());
            }
            LocalDate expiration = null;
            if (item.expiration() != null) {
                expiration = item.expiration().date();
            }
            return new Item(item.itemId(), recognitionStatus, name, category, item.storageType(), item.measureType(), quantity, weight, expiration, item.reviewReasons());
        }
    }
    public record Weight(BigDecimal value, String unit) {}
    public record Error(String code, String message, boolean retryable, Integer retryAfterMs) {
        public static Error from(AiImageAnalysisResponse.Error error) {
            if (error == null) {
                return null;
            }
            return new Error(error.code(), error.message(), error.retryable(), error.retryAfterMs());
        }
    }
}
