package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisCallbackService {
    private final AnalysisJobStore store;
    private final AnalysisCompletionValidator validator;
    private final PushAnalysisCreationService pushes;

    public void receive(String analysisId, AnalysisCompletion completion) {
        try {
            validator.validate(completion);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        AnalysisJob job;
        try {
            job = store.complete(analysisId, completion);
        } catch (CustomException exception) {
            if (exception.getExceptionCode() == AnalysisExceptionCode.RESULT_CONFLICT) {
                throw exception;
            }
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
        if (job.notificationPending()) {
            createCompletionPush(job);
        }
    }

    // 실패하면 503으로 응답해 AI의 콜백 재시도에서 다시 만든다. 이미 만든 발송 작업은 중복 생성하지 않는다.
    private void createCompletionPush(AnalysisJob job) {
        try {
            pushes.create(job.id(), job.userId(), job.expiresAt());
            store.acknowledgeNotification(job.id());
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
