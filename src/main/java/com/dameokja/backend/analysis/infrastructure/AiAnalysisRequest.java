package com.dameokja.backend.analysis.infrastructure;

public record AiAnalysisRequest(String requestId, Image image, String inputHint, String locale, String timezone) {
    public AiAnalysisRequest(String requestId, String objectKey, String sha256) {
        this(requestId, new Image(objectKey, sha256), "AUTO", "ko-KR", "Asia/Seoul");
    }

    public record Image(String objectKey, String sha256) {}
}
