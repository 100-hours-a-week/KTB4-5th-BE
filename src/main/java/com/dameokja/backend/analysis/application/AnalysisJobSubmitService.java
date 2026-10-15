package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
@RequiredArgsConstructor
public class AnalysisJobSubmitService {
    private final ImageAnalysisInputService imageAnalysisInputService;
    private final AnalysisJobStore analysisJobStore;
    private final AiAnalysisClient aiAnalysisClient;

    public AnalysisJob submit(Long userId, List<String> objectKeys, String inputHint) {
        inputHint = inputHint == null ? "AUTO" : inputHint;
        if (userId == null || objectKeys == null || objectKeys.isEmpty() || !Set.of("AUTO", "RECEIPT", "PRODUCT").contains(inputHint)) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        List<AnalysisImage> analysisImages = objectKeys.stream()
                .map(objectKey -> AnalysisImage.pending(objectKey, imageAnalysisInputService.findAnalysisSha256(userId, objectKey))).toList();
        AnalysisJob analysisJob = analysisJobStore.create(userId, inputHint, analysisImages);
        try {
            for (AnalysisImage analysisImage : analysisJob.analysisImages()) {
                analysisJobStore.register(analysisJob.id(), userId, analysisImage.requestId(), submitImage(analysisImage, inputHint));
            }
        } catch (RuntimeException exception) {
            discard(analysisJob, exception);
            throw exception;
        }
        return analysisJobStore.findOwned(analysisJob.id(), userId);
    }

    // 접수에 실패하면 클라이언트는 작업 ID를 받지 못하고, 남은 이미지는 끝나지 않아 보관기간도 시작되지 않는다.
    // 그래서 묶음과 AI ID 색인을 지우며, 이미 접수된 이미지의 콜백은 버린다.
    private void discard(AnalysisJob analysisJob, RuntimeException cause) {
        try {
            analysisJobStore.delete(analysisJob.id());
        } catch (RuntimeException deleteFailure) {
            cause.addSuppressed(deleteFailure);
        }
    }

    private String submitImage(AnalysisImage analysisImage, String inputHint) {
        try {
            AiImageAnalysisSubmitRequest aiImageAnalysisSubmitRequest = new AiImageAnalysisSubmitRequest(analysisImage.requestId(),
                    new AiImageAnalysisSubmitRequest.Image(analysisImage.objectKey(), analysisImage.sha256()), inputHint, "ko-KR", "Asia/Seoul");
            String analysisId = aiAnalysisClient.submit(aiImageAnalysisSubmitRequest).analysisId();
            if (analysisId == null || analysisId.isBlank()) {
                throw new RestClientException("AI 접수 응답에 작업 ID가 없습니다.");
            }
            return analysisId;
        } catch (RestClientException restClientException) {
            ExceptionCode exceptionCode = restClientException instanceof RestClientResponseException restClientResponseException ? switch (restClientResponseException.getStatusCode().value()) {
                case 400 -> GlobalExceptionCode.BAD_REQUEST;
                case 429 -> AnalysisExceptionCode.REQUEST_LIMITED;
                default -> AnalysisExceptionCode.ACCEPTANCE_UNAVAILABLE;
            } : AnalysisExceptionCode.ACCEPTANCE_UNAVAILABLE;
            CustomException customException = new CustomException(exceptionCode);
            customException.initCause(restClientException);
            throw customException;
        }
    }
}
