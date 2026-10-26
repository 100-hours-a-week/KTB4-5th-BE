package com.dameokja.backend.analysis.domain;

public final class AiAnalysisIdentifiers {
    // AI 피드백의 itemId와 AI 분석 ID 저장 계약은 모두 최대 64자다.
    public static final int MAX_LENGTH = 64;

    private AiAnalysisIdentifiers() {}

    public static boolean isValid(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_LENGTH;
    }
}
