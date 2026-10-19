package com.dameokja.backend.notification.domain;

import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public record AnalysisFinished(Long userId, Payload payload) {
    public AnalysisFinished {
        if (userId == null || userId <= 0 || payload == null) {
            throw new IllegalArgumentException("분석 종료 대상 사용자와 내용이 필요합니다.");
        }
    }

    public record Payload(String analysisId, AnalysisStatus status, int totalImageCount, int completedImageCount,
                          int failedImageCount, String expiresAt) {
        public Payload {
            if (analysisId == null || analysisId.isBlank() || expiresAt == null
                    || (status != AnalysisStatus.COMPLETED && status != AnalysisStatus.PARTIALLY_COMPLETED && status != AnalysisStatus.FAILED)) {
                throw new IllegalArgumentException("분석 작업 식별자와 종료 상태·만료 시각이 필요합니다.");
            }
        }
    }

    public static AnalysisFinished of(Long userId, String analysisId, AnalysisStatus status, int total, int completed, int failed, OffsetDateTime expiresAt) {
        String expiry = expiresAt.atZoneSameInstant(BusinessTime.ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new AnalysisFinished(userId, new Payload(analysisId, status, total, completed, failed, expiry));
    }
}
