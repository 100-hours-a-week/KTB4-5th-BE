package com.dameokja.backend.notification.domain;

import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

public record AnalysisImageFinished(Long userId, Payload payload) {
    public AnalysisImageFinished {
        if (userId == null || userId <= 0 || payload == null) {
            throw new IllegalArgumentException("이미지 종료 대상 사용자와 내용이 필요합니다.");
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Payload(String analysisId, String imageObjectKey, AnalysisStatus imageStatus, int totalImageCount,
                          int completedImageCount, int failedImageCount, String expiresAt) {
        public Payload {
            if (analysisId == null || analysisId.isBlank() || imageObjectKey == null || imageObjectKey.isBlank()
                    || (imageStatus != AnalysisStatus.COMPLETED && imageStatus != AnalysisStatus.FAILED) || expiresAt != null) {
                throw new IllegalArgumentException("작업·이미지 식별자와 종료 상태가 필요하며 진행 중 만료 시각은 없어야 합니다.");
            }
        }
    }
}
