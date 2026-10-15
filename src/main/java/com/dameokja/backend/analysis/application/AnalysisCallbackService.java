package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
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

    public void receive(String analysisId, AnalysisCompletion completion) {
        try {
            validator.validate(completion);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        try {
            store.complete(analysisId, completion);
        } catch (IllegalStateException exception) {
            throw new CustomException(AnalysisExceptionCode.RESULT_CONFLICT);
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
