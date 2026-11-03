package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import java.time.Instant;

public record AnalysisRequestedPayload(Instant occurredAt, String requestId, Long userId, Long refrigeratorId, Image image,
        String inputHint, String locale, String timezone) {
    public AnalysisRequestedPayload {
        if (refrigeratorId == null || refrigeratorId <= 0) { throw new IllegalArgumentException("분석 요청의 냉장고 ID가 필요합니다."); }
    }

    public static AnalysisRequestedPayload create(Long userId, Long refrigeratorId, AnalysisImage image, String inputHint, Instant occurredAt) {
        return new AnalysisRequestedPayload(occurredAt, image.requestId(), userId, refrigeratorId,
                new Image(image.objectKey(), image.sha256()), inputHint, "ko-KR", "Asia/Seoul");
    }

    public record Image(String objectKey, String sha256) {}
}
