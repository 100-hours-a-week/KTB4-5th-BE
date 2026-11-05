package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchQueue;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AnalysisJobSubmitService {
    private static final String REQUEST_CONTEXT_VERSION = "analysis-refrigerator-v1";
    private final ImageAnalysisInputService imageAnalysisInputService;
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisDispatchQueue analysisDispatchQueue;
    private final AnalysisDispatchWorker analysisDispatchWorker;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final AnalysisJobAcceptance analysisJobAcceptance;
    private final boolean kafkaSubmissionEnabled;

    public AnalysisJobSubmitService(ImageAnalysisInputService imageAnalysisInputService, AnalysisJobStore analysisJobStore,
            AnalysisDispatchQueue analysisDispatchQueue, AnalysisDispatchWorker analysisDispatchWorker, RefrigeratorAccessService refrigeratorAccessService,
            AnalysisJobAcceptance analysisJobAcceptance, @Value("${analysis.kafka-submission-enabled:false}") boolean kafkaSubmissionEnabled) {
        this.imageAnalysisInputService = imageAnalysisInputService;
        this.analysisJobStore = analysisJobStore;
        this.analysisDispatchQueue = analysisDispatchQueue;
        this.analysisDispatchWorker = analysisDispatchWorker;
        this.refrigeratorAccessService = refrigeratorAccessService;
        this.analysisJobAcceptance = analysisJobAcceptance;
        this.kafkaSubmissionEnabled = kafkaSubmissionEnabled;
    }

    public AnalysisJob submit(Long userId, List<String> objectKeys, String inputHint) {
        inputHint = inputHint == null ? "AUTO" : inputHint;
        if (userId == null || objectKeys == null || objectKeys.isEmpty() || !Set.of("AUTO", "RECEIPT", "PRODUCT").contains(inputHint)) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        if (kafkaSubmissionEnabled) {
            return analysisJobAcceptance.accept(userId, inputHint, validateImages(userId, objectKeys));
        }
        Long refrigeratorId = refrigeratorAccessService.findCurrentForRead(userId).getId();
        String resolvedInputHint = inputHint;
        List<AnalysisImage> analysisImages = validateImages(userId, objectKeys).stream()
                .map(image -> identify(userId, refrigeratorId, image, resolvedInputHint)).toList();
        String analysisJobId = UUID.randomUUID().toString();
        // 전달 큐를 먼저 저장한다. 그 뒤 Redis 작업 생성이 실패하면 사용자는 작업 ID를 받지 못하고, 워커가 남은 전달 행을 실패로 정리한다.
        analysisDispatchQueue.enqueue(analysisJobId, userId, inputHint, analysisImages);
        AnalysisJob analysisJob = analysisJobStore.create(analysisJobId, userId, refrigeratorId, inputHint, analysisImages);
        analysisDispatchWorker.wakeUp();
        return analysisJob;
    }

    private List<AnalysisImage> validateImages(Long userId, List<String> objectKeys) {
        return objectKeys.stream().distinct()
                .map(objectKey -> AnalysisImage.pending(objectKey, imageAnalysisInputService.findAnalysisSha256(userId, objectKey))).toList();
    }

    // 냉장고를 보내지 않던 계약과 구분하고, 동일 사용자·냉장고·사진·힌트의 재시도에는 같은 요청 ID를 사용한다.
    private AnalysisImage identify(Long userId, Long refrigeratorId, AnalysisImage image, String inputHint) {
        String objectKey = image.objectKey();
        String sha256 = image.sha256();
        String requestId = UUID.nameUUIDFromBytes(String.join("\n", REQUEST_CONTEXT_VERSION, userId.toString(), refrigeratorId.toString(), objectKey, String.valueOf(sha256), inputHint)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return AnalysisImage.pending(objectKey, sha256, requestId);
    }
}
