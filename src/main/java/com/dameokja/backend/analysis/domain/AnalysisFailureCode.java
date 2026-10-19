package com.dameokja.backend.analysis.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

// AI에 전달하지 못했거나 결과를 받지 못해 BE가 이미지를 실패로 기록할 때 결과 조회의 이미지별 error.code로 노출된다.
@Getter
@AllArgsConstructor
public enum AnalysisFailureCode {
    ANALYSIS_EXPIRED("분석 처리 기한이 지나 완료하지 못했습니다. 다시 시도해 주세요.", true),
    // AI가 요청 자체를 거절했으므로 같은 이미지로 다시 시도해도 결과가 같다.
    ANALYSIS_REJECTED("분석할 수 없는 이미지입니다. 다른 사진으로 다시 시도하거나 직접 입력해 주세요.", false),
    ANALYSIS_UNAVAILABLE("일시적인 장애로 분석을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.", true);

    private final String message;
    private final boolean retryable;

    public AnalysisCompletionFailure toFailure() {
        return new AnalysisCompletionFailure(name(), message, retryable, null);
    }
}
