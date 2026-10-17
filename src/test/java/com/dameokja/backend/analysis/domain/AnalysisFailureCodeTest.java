package com.dameokja.backend.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AnalysisFailureCodeTest {
    @ParameterizedTest
    @EnumSource(AnalysisFailureCode.class)
    void exposesItsNameAndMessageAsTheImageError(AnalysisFailureCode code) {
        AnalysisCompletionFailure failure = code.toFailure();
        assertThat(failure.code()).isEqualTo(code.name());
        assertThat(failure.message()).isEqualTo(code.getMessage()).isNotBlank();
        assertThat(failure.retryAfterMs()).isNull();
    }

    @Test
    void onlyARejectedRequestIsNotRetryable() {
        assertThat(AnalysisFailureCode.ANALYSIS_REJECTED.isRetryable()).isFalse();
        assertThat(AnalysisFailureCode.ANALYSIS_EXPIRED.isRetryable()).isTrue();
        assertThat(AnalysisFailureCode.ANALYSIS_UNAVAILABLE.isRetryable()).isTrue();
    }
}
