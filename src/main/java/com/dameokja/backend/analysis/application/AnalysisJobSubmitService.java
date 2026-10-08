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
        for (AnalysisImage analysisImage : analysisJob.analysisImages()) {
            analysisJobStore.register(analysisJob.id(), userId, analysisImage.requestId(), submitImage(analysisImage, inputHint));
        }
        return analysisJobStore.findOwned(analysisJob.id(), userId);
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
