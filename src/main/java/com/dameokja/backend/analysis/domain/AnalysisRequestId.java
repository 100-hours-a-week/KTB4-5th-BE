package com.dameokja.backend.analysis.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class AnalysisRequestId {
    // 냉장고를 포함하지 않던 이전 계약과 요청 ID를 구분한다.
    private static final String REQUEST_CONTEXT_VERSION = "analysis-refrigerator-v1";

    private AnalysisRequestId() {}

    public static String generate(Long userId, Long refrigeratorId, AnalysisImage analysisImage, String inputHint) {
        return UUID.nameUUIDFromBytes(String.join("\n", REQUEST_CONTEXT_VERSION, userId.toString(), refrigeratorId.toString(),
                analysisImage.objectKey(), String.valueOf(analysisImage.sha256()), inputHint).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
