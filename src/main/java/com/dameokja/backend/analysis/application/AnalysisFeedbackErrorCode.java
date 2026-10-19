package com.dameokja.backend.analysis.application;

// 피드백 행의 last_error_code에만 기록한다.
public enum AnalysisFeedbackErrorCode {
    AI_BUSY, AI_UNAVAILABLE, AI_REJECTED, EXPIRED
}
