package com.dameokja.backend.analysis.domain;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "분석 항목에서 사용자가 확인할 필드와 사유", requiredProperties = {"field", "code", "messages"})
public record AnalysisReviewReason(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Field field,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Code code,
        @ArraySchema(minItems = 1, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(minLength = 1)) List<String> messages) {
    public AnalysisReviewReason {
        if (field == null || code == null || messages == null || messages.isEmpty()
                || messages.stream().anyMatch(message -> message == null || message.isBlank())) {
            throw new IllegalArgumentException("검토 사유에는 필드, 코드와 비어 있지 않은 메시지 목록이 필요합니다.");
        }
        messages = List.copyOf(messages);
    }

    public enum Field {
        ITEM, NAME, CATEGORY, STORAGE_TYPE, MEASURE_TYPE, QUANTITY, WEIGHT, EXPIRATION, IMAGE
    }

    public enum Code {
        ITEM_REVIEW_REQUIRED, IMAGE_QUALITY_REVIEW_REQUIRED, EXPIRATION_NOT_FOUND, EXPIRATION_ESTIMATED,
        EXPIRATION_BASIS_FALLBACK, QUANTITY_DEFAULTED, CATEGORY_FUZZY_MATCHED
    }
}
