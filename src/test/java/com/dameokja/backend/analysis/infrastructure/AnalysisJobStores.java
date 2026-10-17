package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;

// AI 분석 하나에 작업 하나만 묶인 테스트에서 분석 ID로 작업을 찾아 쓴다.
public final class AnalysisJobStores {
    private AnalysisJobStores() {}

    public static AnalysisJob complete(AnalysisJobStore store, String analysisId, AnalysisCompletion completion) {
        return store.complete(onlyJobId(store, analysisId), analysisId, completion);
    }

    public static AnalysisJob findByAiAnalysisId(AnalysisJobStore store, String analysisId) {
        return store.find(onlyJobId(store, analysisId)).orElseThrow(() -> new CustomException(AnalysisExceptionCode.NOT_FOUND));
    }

    private static String onlyJobId(AnalysisJobStore store, String analysisId) {
        return store.findJobIdsByAiAnalysisId(analysisId).stream().findFirst().orElseThrow(() -> new CustomException(AnalysisExceptionCode.NOT_FOUND));
    }
}
