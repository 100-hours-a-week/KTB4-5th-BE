package com.dameokja.backend.analysis.domain;

import com.dameokja.backend.global.util.BusinessTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record AnalysisCompletion(AnalysisStatus status, OffsetDateTime completedAt, Result result, Error error) {
    public AnalysisCompletion {
        completedAt = Objects.requireNonNull(completedAt, "분석 종료 시각이 필요합니다.")
                .atZoneSameInstant(BusinessTime.ZONE).toOffsetDateTime();
        if (status == AnalysisStatus.COMPLETED) {
            if (result == null || error != null) {
                throw new IllegalArgumentException("완료된 분석에는 결과만 필요합니다.");
            }
        } else if (status != AnalysisStatus.FAILED || result != null || error == null) {
            throw new IllegalArgumentException("실패한 분석에는 오류만 필요합니다.");
        }
    }

    public record Result(String documentType, List<Item> items) {
        public Result {
            items = List.copyOf(items);
        }
    }

    public record Item(String itemId, RecognitionStatus displayStatus, String name, String category, String storageType,
            String measureType, Integer quantity, Weight weight, Expiration expiration, List<AnalysisReviewReason> reviewReasons) {
        public Item {
            reviewReasons = List.copyOf(reviewReasons);
        }
    }

    public record Weight(BigDecimal value, String unit) {}

    public record Expiration(LocalDate date, String dateType, EstimatedRange estimatedRange) {}

    public record EstimatedRange(@JsonProperty("from") LocalDate from, @JsonProperty("to") LocalDate to) {}

    public record Error(String code, String message, boolean retryable, Integer retryAfterMs) {}
}
