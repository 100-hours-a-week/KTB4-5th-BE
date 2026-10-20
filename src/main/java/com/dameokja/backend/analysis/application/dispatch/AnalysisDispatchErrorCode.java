package com.dameokja.backend.analysis.application.dispatch;

// 재시도 사유로 전달 행의 last_error_code에만 기록한다. 사용자에게 보이는 실패 코드는 AnalysisFailureCode다.
public enum AnalysisDispatchErrorCode {
    AI_BUSY, AI_UNAVAILABLE, AI_JOB_LOST, JOB_MISSING, JOB_NOT_READY, REGISTER_FAILED
}
