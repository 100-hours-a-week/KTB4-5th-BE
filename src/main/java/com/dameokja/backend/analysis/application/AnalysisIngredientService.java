package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.*;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.*;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.create.*;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisIngredientService {
    private static final int MAX_ITEMS = 20;
    private final AnalysisJobStore analysisJobStore;
    private final IngredientCreateService ingredientCreateService;
    private final AiAnalysisClient aiAnalysisClient;

    // 외부 전송과 결과 삭제는 재고 등록 트랜잭션이 커밋된 후 수행한다.
    public IngredientWriteResult create(Long userId, Long refrigeratorId, AnalysisIngredientCommand request) {
        AnalysisJob job = analysisJobStore.findOwned(request.analysisId(), userId);
        List<Feedback> feedbacks = prepareRegistration(job, request);
        IngredientWriteResult result = ingredientCreateService.create(userId, refrigeratorId, feedbacks.stream().map(Feedback::command).toList());
        sendAndCleanup(job.id(), feedbacks);
        return result;
    }

    private List<Feedback> prepareRegistration(AnalysisJob job, AnalysisIngredientCommand request) {
        List<Feedback> feedbacks = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (AnalysisIngredientCommand.Image input : request.images()) {
            validateRegistration(keys.add(input.imageObjectKey()) && !input.items().isEmpty());
            AnalysisImage image = job.analysisImages().stream().filter(candidate -> candidate.objectKey().equals(input.imageObjectKey()))
                    .findFirst().orElseThrow(() -> new CustomException(AnalysisExceptionCode.INVALID_REGISTRATION));
            validateRegistration(image.completion() != null && image.completion().status() == AnalysisStatus.COMPLETED);
            AnalysisCompletionSuccess result = image.completion().result();
            validateRegistration(result.documentType() == AnalysisDocumentType.RECEIPT || result.documentType() == AnalysisDocumentType.PRODUCT);
            Set<String> ids = new HashSet<>();
            for (AnalysisIngredientCommand.Item item : input.items()) {
                validateRegistration(ids.add(item.itemId()) && result.items().stream().anyMatch(original -> original.itemId().equals(item.itemId())));
                RegistrationSource source = result.documentType() == AnalysisDocumentType.RECEIPT ? RegistrationSource.RECEIPT : RegistrationSource.FOOD_IMAGE;
                feedbacks.add(new Feedback(image.aiAnalysisId(), item.itemId(), item.toCommand(source)));
            }
        }
        validateRegistration(!feedbacks.isEmpty() && feedbacks.size() <= MAX_ITEMS);
        return feedbacks;
    }

    private void sendAndCleanup(String jobId, List<Feedback> feedbacks) {
        boolean success = true;
        for (Feedback feedback : feedbacks) {
            try {
                aiAnalysisClient.feedback(feedback.aiId(), feedback.request());
            } catch (RuntimeException exception) {
                success = false;
                log.warn("재고 등록 후 AI 피드백 실패: jobId={}, aiId={}, itemId={}", jobId, feedback.aiId(), feedback.itemId(), exception);
            }
        }
        if (success) {
            try {
                analysisJobStore.delete(jobId);
            } catch (RuntimeException exception) {
                log.warn("재고 등록 후 분석 결과 삭제 실패: jobId={}", jobId, exception);
            }
        }
    }

    private void validateRegistration(boolean valid) {
        if (!valid) { throw new CustomException(AnalysisExceptionCode.INVALID_REGISTRATION); }
    }

    private record Feedback(String aiId, String itemId, IngredientCreateCommand command) {
        AiAnalysisFeedbackRequest request() {
            return AiAnalysisFeedbackRequest.corrected(itemId, AiAnalysisFeedbackRequest.Confirmed.userConfirmed(command.name(), command.category(),
                    command.storageType(), command.measureType(), command.quantity(), command.weightValue(), command.weightUnit(), command.expirationDate()));
        }
    }
}
