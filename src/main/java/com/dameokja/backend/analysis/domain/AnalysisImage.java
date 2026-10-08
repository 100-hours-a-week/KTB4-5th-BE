package com.dameokja.backend.analysis.domain;

import java.util.UUID;

public record AnalysisImage(String objectKey, String sha256, String requestId, String aiAnalysisId) {
    public static AnalysisImage pending(String objectKey, String sha256) { return new AnalysisImage(objectKey, sha256, UUID.randomUUID().toString(), null); }

    public AnalysisImage register(String analysisId) {
        if (analysisId == null || analysisId.isBlank()) {
            throw new IllegalArgumentException("AI 작업 ID가 필요합니다.");
        }
        if (aiAnalysisId != null && !aiAnalysisId.equals(analysisId)) {
            throw new IllegalStateException("이미 접수된 이미지의 AI 작업 ID는 바꿀 수 없습니다.");
        }
        return new AnalysisImage(objectKey, sha256, requestId, analysisId);
    }
}
