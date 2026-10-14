package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisReviewReason;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AnalysisImageResult(String imageObjectKey, AnalysisStatus status, RecognitionStatus recognitionStatus, AnalysisDocumentType documentType,
        List<Item> items, Error error) {
    public static AnalysisImageResult fromStored(AnalysisImage image) {
        AnalysisCompletion completion = image.completion();
        if (completion == null) {
            AnalysisStatus status = image.aiAnalysisId() == null ? AnalysisStatus.QUEUED : AnalysisStatus.PROCESSING;
            return new AnalysisImageResult(image.objectKey(), status, null, null, List.of(), null);
        }
        if (completion.result() == null) {
            return new AnalysisImageResult(image.objectKey(), completion.status(), null, null, List.of(), Error.from(completion.error()));
        }
        List<Item> items = completion.result().items().stream().map(Item::from).toList();
        RecognitionStatus recognition = RecognitionStatus.aggregate(items.stream().map(Item::displayStatus).toList());
        return new AnalysisImageResult(image.objectKey(), completion.status(), recognition, completion.result().documentType(), items, Error.from(completion.error()));
    }

    public record Item(String itemId, RecognitionStatus displayStatus, String name, IngredientCategory category, StorageType storageType, MeasureType measureType,
            Integer quantity, Weight weight, LocalDate expiration, List<AnalysisReviewReason> reviewReasons) {
        public Item {
            reviewReasons = List.copyOf(reviewReasons);
        }

        public static Item from(AnalysisCompletionItem item) {
            return new Item(item.itemId(), item.displayStatus(), item.name(), item.category(), item.storageType(), item.measureType(),
                    item.quantity(), Weight.from(item.weight()), item.expiration().date(), item.reviewReasons());
        }
    }

    public record Weight(BigDecimal value, WeightUnit unit) {
        public static Weight from(AnalysisCompletionItem.Weight weight) {
            return new Weight(weight.value(), weight.unit());
        }
    }

    public record Error(String code, String message, boolean retryable, Integer retryAfterMs) {
        public static Error from(AnalysisCompletionFailure error) {
            if (error == null) {
                return null;
            }
            return new Error(error.code(), error.message(), error.retryable(), error.retryAfterMs());
        }
    }
}
