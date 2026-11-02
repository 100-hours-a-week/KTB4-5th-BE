package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import java.time.Instant;
import java.util.UUID;

public record AnalysisRequestedEvent(String messageId, Payload payload) {
    public static AnalysisRequestedEvent create(Long userId, Long refrigeratorId, AnalysisImage image, String inputHint, Instant occurredAt) {
        return new AnalysisRequestedEvent(UUID.randomUUID().toString(),
                new Payload("AnalysisRequested", occurredAt, image.requestId(), userId, refrigeratorId,
                        new Image(image.objectKey(), image.sha256()), inputHint, "ko-KR", "Asia/Seoul"));
    }

    public record Payload(String eventType, Instant occurredAt, String requestId, Long userId, Long refrigeratorId, Image image,
            String inputHint, String locale, String timezone) {}

    public record Image(String objectKey, String sha256) {}
}
