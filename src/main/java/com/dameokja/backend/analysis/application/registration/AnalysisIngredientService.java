package com.dameokja.backend.analysis.application.registration;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackQueue;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisIngredientService {
    private static final int MAX_ITEMS = 20;
    private final AnalysisJobStore analysisJobStore;
    private final IngredientCreateService ingredientCreateService;
    private final AnalysisFeedbackQueue analysisFeedbackQueue;
    private final TransactionTemplate transactionTemplate;

    // 피드백은 재고와 같은 트랜잭션으로 큐에 넣어 등록이 커밋되면 함께 남게 한다. Redis 결과 삭제는 되돌릴 수 없어 커밋 뒤에 한다.
    public IngredientWriteResult create(Long userId, Long refrigeratorId, AnalysisIngredientCommand request) {
        AnalysisJob job = analysisJobStore.findOwned(request.analysisId(), userId);
        List<Feedback> feedbacks = prepareRegistration(job, request);
        IngredientWriteResult result = transactionTemplate.execute(status -> registerWithFeedbacks(userId, refrigeratorId, feedbacks));
        deleteResult(job.id());
        return result;
    }

    private List<Feedback> prepareRegistration(AnalysisJob job, AnalysisIngredientCommand request) {
        List<Feedback> feedbacks = new ArrayList<>();
        Set<String> imageKeys = new HashSet<>();
        for (AnalysisIngredientCommand.Image input : request.images()) {
            validateRegistration(imageKeys.add(input.imageObjectKey()) && !input.items().isEmpty());
            AnalysisImage image = findRegistrableImage(job, input.imageObjectKey());
            feedbacks.addAll(prepareImageRegistration(image, input.items()));
        }
        validateRegistration(!feedbacks.isEmpty() && feedbacks.size() <= MAX_ITEMS);
        return feedbacks;
    }

    private AnalysisImage findRegistrableImage(AnalysisJob job, String imageObjectKey) {
        AnalysisImage image = job.analysisImages().stream()
                .filter(candidate -> candidate.objectKey().equals(imageObjectKey))
                .findFirst().orElseThrow(() -> new CustomException(AnalysisExceptionCode.INVALID_REGISTRATION));
        validateRegistration(image.completion() != null && image.completion().status() == AnalysisStatus.COMPLETED);
        AnalysisDocumentType documentType = image.completion().result().documentType();
        validateRegistration(documentType == AnalysisDocumentType.RECEIPT || documentType == AnalysisDocumentType.PRODUCT);
        return image;
    }

    private List<Feedback> prepareImageRegistration(AnalysisImage image, List<AnalysisIngredientCommand.Item> items) {
        AnalysisCompletionSuccess result = image.completion().result();
        Set<String> originalItemIds = result.items().stream().map(AnalysisCompletionItem::itemId).collect(Collectors.toSet());
        Set<String> requestedItemIds = new HashSet<>();
        RegistrationSource source = result.documentType() == AnalysisDocumentType.RECEIPT ? RegistrationSource.RECEIPT : RegistrationSource.FOOD_IMAGE;
        List<Feedback> feedbacks = new ArrayList<>();
        for (AnalysisIngredientCommand.Item item : items) {
            validateRegistration(requestedItemIds.add(item.itemId()) && originalItemIds.contains(item.itemId()));
            feedbacks.add(new Feedback(image.aiAnalysisId(), item.itemId(), item.toCommand(source)));
        }
        return feedbacks;
    }

    private IngredientWriteResult registerWithFeedbacks(Long userId, Long refrigeratorId, List<Feedback> feedbacks) {
        IngredientWriteResult result = ingredientCreateService.create(userId, refrigeratorId, feedbacks.stream().map(Feedback::command).toList());
        feedbacks.forEach(feedback -> analysisFeedbackQueue.enqueue(feedback.aiId(), feedback.request()));
        return result;
    }

    // 결과가 남아 있으면 같은 항목을 다시 등록할 수 있으므로, 피드백 전송을 기다리지 않고 등록이 커밋되면 바로 지운다.
    private void deleteResult(String jobId) {
        try {
            analysisJobStore.delete(jobId);
        } catch (RuntimeException exception) {
            log.warn("재고 등록 후 분석 결과 삭제 실패: jobId={}", jobId, exception);
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
