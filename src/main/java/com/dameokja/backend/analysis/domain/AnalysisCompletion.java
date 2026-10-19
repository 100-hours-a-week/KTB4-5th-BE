package com.dameokja.backend.analysis.domain;

import com.dameokja.backend.global.util.BusinessTime;
import java.time.OffsetDateTime;
import java.util.Objects;

public record AnalysisCompletion(AnalysisStatus status, OffsetDateTime completedAt, AnalysisCompletionSuccess result, AnalysisCompletionFailure error) {
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
}
