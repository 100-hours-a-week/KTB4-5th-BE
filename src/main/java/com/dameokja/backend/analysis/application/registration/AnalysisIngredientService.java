package com.dameokja.backend.analysis.application.registration;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackOutbox;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.analysis.domain.*;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.create.*;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisIngredientService {
    private static final int MAX_ITEMS = 20;
    private static final int MAX_FEEDBACK_ITEMS = 100;
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final IngredientCreateService ingredientCreateService;
    private final AnalysisFeedbackOutbox analysisFeedbackOutbox;
    private final RefrigeratorAccessService refrigeratorAccessService;

    @Transactional
    public IngredientWriteResult create(Long userId, Long refrigeratorId, AnalysisIngredientCommand request) {
        // 사용자→냉장고→참여자 순서를 먼저 지킨 뒤 분석 작업을 잠가 동시 등록이 결과를 중복 소비하지 못하게 한다.
        refrigeratorAccessService.lockForWrite(userId, refrigeratorId);
        AnalysisJob analysisJob = analysisJobStateAccess.findOwnedForUpdate(request.analysisId(), userId);
        validateRegistration(analysisJob.refrigeratorId() == null || analysisJob.refrigeratorId().equals(refrigeratorId));
        List<Feedback> feedbacks = prepareRegistration(analysisJob, request);
        IngredientWriteResult result = registerWithFeedbacks(analysisJob, refrigeratorId, feedbacks);
        analysisJobStateAccess.deleteOwned(analysisJob.id(), userId);
        return result;
    }

    private List<Feedback> prepareRegistration(AnalysisJob job, AnalysisIngredientCommand request) {
        List<Feedback> feedbacks = new ArrayList<>();
        Set<String> imageKeys = new HashSet<>();
        for (AnalysisIngredientCommand.Image input : request.images()) {
            boolean firstImage = imageKeys.add(input.imageObjectKey());
            validateRegistration(firstImage && !input.items().isEmpty());
            AnalysisImage image = findRegistrableImage(job, input.imageObjectKey());
            feedbacks.addAll(prepareImageRegistration(image, input.items()));
        }
        validateRegistration(!feedbacks.isEmpty() && feedbacks.size() <= MAX_ITEMS);
        addDeletedFeedbacks(job, feedbacks);
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
        validateRegistration(AiAnalysisIdentifiers.isValid(image.aiAnalysisId()));
        AnalysisCompletionSuccess result = image.completion().result();
        Map<String, AnalysisCompletionItem> originals = validatedOriginals(image);
        Set<String> requestedItemIds = new HashSet<>();
        RegistrationSource source = result.documentType() == AnalysisDocumentType.RECEIPT ? RegistrationSource.RECEIPT : RegistrationSource.FOOD_IMAGE;
        List<Feedback> feedbacks = new ArrayList<>();
        for (AnalysisIngredientCommand.Item item : items) {
            if (item.itemId() != null) {
                validateRegistration(AiAnalysisIdentifiers.isValid(item.itemId()) && originals.containsKey(item.itemId()));
                boolean firstRequest = requestedItemIds.add(item.itemId());
                validateRegistration(firstRequest);
            }
            feedbacks.add(new Feedback(image.aiAnalysisId(), item.itemId(), originals.get(item.itemId()), item.toCommand(source)));
        }
        return feedbacks;
    }

    private Map<String, AnalysisCompletionItem> validatedOriginals(AnalysisImage image) {
        validateRegistration(AiAnalysisIdentifiers.isValid(image.aiAnalysisId()));
        Map<String, AnalysisCompletionItem> originals = new LinkedHashMap<>();
        for (AnalysisCompletionItem item : image.completion().result().items()) {
            validateRegistration(AiAnalysisIdentifiers.isValid(item.itemId()) && !originals.containsKey(item.itemId()));
            originals.put(item.itemId(), item);
        }
        return originals;
    }

    private void addDeletedFeedbacks(AnalysisJob job, List<Feedback> feedbacks) {
        Set<String> analysisIds = new HashSet<>();
        for (AnalysisImage image : job.analysisImages()) {
            if (image.completion() == null || image.completion().status() != AnalysisStatus.COMPLETED) { continue; }
            AnalysisDocumentType type = image.completion().result().documentType();
            if (type != AnalysisDocumentType.RECEIPT && type != AnalysisDocumentType.PRODUCT) { continue; }
            boolean firstAnalysis = analysisIds.add(image.aiAnalysisId());
            validateRegistration(firstAnalysis);
            Set<String> selected = feedbacks.stream().filter(feedback -> feedback.aiId().equals(image.aiAnalysisId()))
                    .map(Feedback::itemId).collect(Collectors.toSet());
            for (AnalysisCompletionItem original : validatedOriginals(image).values()) {
                if (!selected.contains(original.itemId())) {
                    feedbacks.add(new Feedback(image.aiAnalysisId(), original.itemId(), original, null));
                }
            }
            validateRegistration(feedbacks.stream().filter(feedback -> feedback.aiId().equals(image.aiAnalysisId())).count() <= MAX_FEEDBACK_ITEMS);
        }
    }

    private IngredientWriteResult registerWithFeedbacks(AnalysisJob analysisJob, Long refrigeratorId, List<Feedback> feedbacks) {
        IngredientWriteResult result = ingredientCreateService.create(analysisJob.userId(), refrigeratorId, feedbacks.stream().map(Feedback::command).filter(java.util.Objects::nonNull).toList());
        Map<String, List<AiAnalysisFeedbackRequest.Item>> itemsByAnalysis = new LinkedHashMap<>();
        int resultIndex = 0;
        for (Feedback feedback : feedbacks) {
            String storedName = null;
            if (feedback.command() != null) {
                storedName = result.items().get(resultIndex).ingredient().getName();
                resultIndex++;
            }
            itemsByAnalysis.computeIfAbsent(feedback.aiId(), ignored -> new ArrayList<>()).add(feedback.request(storedName));
        }
        itemsByAnalysis.forEach((analysisId, items) -> {
            String requestId = analysisJob.analysisImages().stream().filter(analysisImage -> analysisId.equals(analysisImage.aiAnalysisId()))
                    .findFirst().orElseThrow().requestId();
            analysisFeedbackOutbox.enqueue(analysisJob.userId(), requestId, analysisId, new AiAnalysisFeedbackRequest(refrigeratorId, true, items));
        });
        return result;
    }

    private void validateRegistration(boolean valid) {
        if (!valid) { throw new CustomException(AnalysisExceptionCode.INVALID_REGISTRATION); }
    }

    private record Feedback(String aiId, String itemId, AnalysisCompletionItem original, IngredientCreateCommand command) {
        AiAnalysisFeedbackRequest.Item request(String storedName) {
            if (command == null) { return new AiAnalysisFeedbackRequest.Item(itemId, "DELETED", null); }
            return AnalysisFeedbackItems.confirmed(itemId, original, AiAnalysisFeedbackRequest.Confirmed.userConfirmed(storedName, command.category(),
                    command.storageType(), command.measureType(), command.quantity(), command.weightValue(), command.weightUnit(), command.expirationDate()));
        }
    }
}
