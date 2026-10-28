package com.dameokja.backend.analysis.infrastructure.ai;

public record AiImageAnalysisSubmitRequest(String requestId, Long userId, Image image, String inputHint, String locale, String timezone) {
    public AiImageAnalysisSubmitRequest(String requestId, Long userId, String objectKey, String sha256) {
        this(requestId, userId, new Image(objectKey, sha256), "AUTO", "ko-KR", "Asia/Seoul");
    }

    public record Image(String objectKey, String sha256) {}
}
