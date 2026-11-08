package com.dameokja.backend.analysis.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AiImageAnalysisSubmitRequest(String requestId, Image image, String inputHint, String locale, String timezone,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long refrigeratorId) {
    public AiImageAnalysisSubmitRequest(String requestId, String objectKey, String sha256) {
        this(requestId, new Image(objectKey, sha256), "AUTO", "ko-KR", "Asia/Seoul", null);
    }

    public record Image(String objectKey, String sha256) {}
}
