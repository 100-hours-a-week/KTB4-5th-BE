package com.dameokja.backend.analysis.application;

// ANALYSIS_로 시작하는 값은 실패한 이미지의 error.code로 사용자에게도 보인다.
public final class AnalysisDispatchErrorCode {
    public static final String ANALYSIS_EXPIRED = "ANALYSIS_EXPIRED";
    public static final String ANALYSIS_REJECTED = "ANALYSIS_REJECTED";
    public static final String ANALYSIS_UNAVAILABLE = "ANALYSIS_UNAVAILABLE";
    public static final String AI_BUSY = "AI_BUSY";
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String AI_JOB_LOST = "AI_JOB_LOST";
    public static final String JOB_MISSING = "JOB_MISSING";
    public static final String JOB_NOT_READY = "JOB_NOT_READY";
    public static final String REGISTER_FAILED = "REGISTER_FAILED";

    private AnalysisDispatchErrorCode() {}
}
