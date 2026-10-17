package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisCallbackParking;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisCallbackService {
    private final AnalysisJobStore store;
    private final AnalysisCompletionValidator validator;
    private final AnalysisCallbackParking analysisCallbackParking;
    private final AnalysisCompletionApplier analysisCompletionApplier;

    public void receive(String analysisId, AnalysisCompletion completion) {
        try {
            validator.validate(completion);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        if (!registeredOrParked(analysisId, completion)) {
            return;
        }
        analysisCompletionApplier.apply(analysisId, completion);
    }

    // AI 접수 응답이 유실되면 콜백이 AI ID 등록보다 먼저 온다. AI 콜백 재시도는 1초 안에 끝나므로 503 대신 보관하고,
    // 같은 requestId 재전송으로 ID가 등록될 때 전송 워커가 적용한다.
    private boolean registeredOrParked(String analysisId, AnalysisCompletion completion) {
        try {
            if (store.isRegistered(analysisId)) {
                return true;
            }
            analysisCallbackParking.park(analysisId, completion);
            // 등록이 보관과 동시에 일어나면 워커가 보관을 보기 전에 끝났을 수 있어 보관 뒤 다시 확인한다.
            return store.isRegistered(analysisId);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
