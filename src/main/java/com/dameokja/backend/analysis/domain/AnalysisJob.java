package com.dameokja.backend.analysis.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record AnalysisJob(String id, Long userId, String inputHint, List<AnalysisImage> analysisImages,
        OffsetDateTime submittedAt, OffsetDateTime expiresAt) {
    public AnalysisJob {
        analysisImages = List.copyOf(analysisImages);
    }

    public AnalysisJob register(String requestId, String analysisId) {
        if (analysisImages.stream().noneMatch(analysisImage -> analysisImage.requestId().equals(requestId))) {
            throw new IllegalArgumentException("작업에 속하지 않은 이미지 요청입니다.");
        }
        List<AnalysisImage> analysisImages = this.analysisImages.stream()
                .map(analysisImage -> analysisImage.requestId().equals(requestId) ? analysisImage.register(analysisId) : analysisImage).toList();
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt);
    }
}
