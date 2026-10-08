package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResult;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AnalysisImageResult(String imageObjectKey, AnalysisStatus status, RecognitionStatus recognitionStatus, DocumentType documentType,
        ImageQuality imageQuality, List<Item> items, Error error) {
    public static AnalysisImageResult from(String imageObjectKey, AnalysisStatus analysisStatus, AiImageAnalysisResult aiImageAnalysisResult, AiImageAnalysisResponse.Error error) {
        if (aiImageAnalysisResult == null) {
            return new AnalysisImageResult(imageObjectKey, analysisStatus, null, null, null, List.of(), Error.from(error));
        }
        DocumentType documentType = null;
        if (aiImageAnalysisResult.documentType() != null) {
            documentType = new DocumentType(aiImageAnalysisResult.documentType().value(), aiImageAnalysisResult.documentType().confidence());
        }
        ImageQuality imageQuality = null;
        if (aiImageAnalysisResult.imageQuality() != null) {
            imageQuality = new ImageQuality(aiImageAnalysisResult.imageQuality().score(), aiImageAnalysisResult.imageQuality().issues());
        }
        List<Item> items = aiImageAnalysisResult.items().stream().map(Item::from).toList();
        List<RecognitionStatus> recognitionStatuses = items.stream().map(Item::displayStatus).toList();
        RecognitionStatus recognitionStatus = List.of(RecognitionStatus.UNRECOGNIZED, RecognitionStatus.NEEDS_REVIEW,
                RecognitionStatus.AI_ESTIMATED, RecognitionStatus.RECOGNIZED).stream()
                .filter(recognitionStatuses::contains).findFirst().orElse(RecognitionStatus.UNRECOGNIZED);
        return new AnalysisImageResult(imageObjectKey, analysisStatus, recognitionStatus, documentType, imageQuality, items, Error.from(error));
    }

    public record DocumentType(String value, double confidence) {}
    public record ImageQuality(double score, List<String> issues) {}
    public record Item(String itemId, RecognitionStatus displayStatus, Name name, Category category, String storageType, String measureType,
            Quantity quantity, Weight weight, Expiration expiration, List<String> reviewReasons) {
        public static Item from(AiImageAnalysisResult.Item item) {
            if (item.displayStatus() == null) {
                throw new IllegalArgumentException("AI 항목 표시 상태가 없습니다.");
            }
            RecognitionStatus recognitionStatus = RecognitionStatus.valueOf(item.displayStatus());
            Name name = new Name(item.name().value(), item.name().confidence());
            Category category = new Category(item.category().value(), item.category().confidence());
            Quantity quantity = null;
            if (item.quantity() != null) {
                quantity = new Quantity(item.quantity().value(), item.quantity().confidence());
            }
            Weight weight = null;
            if (item.weight() != null) {
                weight = new Weight(item.weight().value(), item.weight().unit(), item.weight().confidence());
            }
            Expiration expiration = null;
            if (item.expiration() != null) {
                expiration = new Expiration(item.expiration().date(), item.expiration().confidence());
            }
            return new Item(item.itemId(), recognitionStatus, name, category, item.storageType(), item.measureType(), quantity, weight, expiration, item.reviewReasons());
        }
    }
    public record Name(String value, double confidence) {}
    public record Category(String value, double confidence) {}
    public record Quantity(int value, double confidence) {}
    public record Weight(BigDecimal value, String unit, double confidence) {}
    public record Expiration(LocalDate date, Double confidence) {}
    public record Error(String code, String message, boolean retryable, Integer retryAfterMs) {
        public static Error from(AiImageAnalysisResponse.Error error) {
            if (error == null) {
                return null;
            }
            return new Error(error.code(), error.message(), error.retryable(), error.retryAfterMs());
        }
    }
}
