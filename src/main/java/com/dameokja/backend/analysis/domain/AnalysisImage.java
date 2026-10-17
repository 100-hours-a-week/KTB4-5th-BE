package com.dameokja.backend.analysis.domain;

import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import java.util.UUID;

public record AnalysisImage(String objectKey, String sha256, String requestId, String aiAnalysisId, AnalysisCompletion completion) {
    public static AnalysisImage pending(String objectKey, String sha256) { return new AnalysisImage(objectKey, sha256, UUID.randomUUID().toString(), null, null); }

    public AnalysisImage register(String analysisId) {
        if (analysisId == null || analysisId.isBlank()) {
            throw new IllegalArgumentException("AI 작업 ID가 필요합니다.");
        }
        // AI가 재시작으로 작업을 잃으면 같은 requestId 재전송에 새 ID를 줄 수 있다. 종료 결과가 생긴 뒤에는 바꾸지 않는다.
        if (completion != null && !analysisId.equals(aiAnalysisId)) {
            throw new IllegalStateException("종료된 이미지의 AI 작업 ID는 바꿀 수 없습니다.");
        }
        return new AnalysisImage(objectKey, sha256, requestId, analysisId, completion);
    }

    public AnalysisImage complete(AnalysisCompletion completion) {
        if (aiAnalysisId == null || completion == null) {
            throw new IllegalArgumentException("접수된 분석의 종료 결과가 필요합니다.");
        }
        return withCompletion(completion);
    }

    public AnalysisImage fail(AnalysisCompletion failure) {
        if (failure == null || failure.status() != AnalysisStatus.FAILED) {
            throw new IllegalArgumentException("실패 결과가 필요합니다.");
        }
        return withCompletion(failure);
    }

    private AnalysisImage withCompletion(AnalysisCompletion completion) {
        // @Repository 예외 변환은 IllegalStateException을 DataAccessException으로 바꿔 일시 장애와 구분할 수 없게 하므로 전용 예외로 알린다.
        if (this.completion != null && !this.completion.equals(completion)) {
            throw new CustomException(AnalysisExceptionCode.RESULT_CONFLICT);
        }
        return new AnalysisImage(objectKey, sha256, requestId, aiAnalysisId, completion);
    }
}
