package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResponse;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
@RequiredArgsConstructor
public class AnalysisJobQueryService {
    private final AnalysisJobStore analysisJobStore;
    private final AiAnalysisClient aiAnalysisClient;

    public AnalysisJobResult get(Long userId, String analysisId) {
        AnalysisJob analysisJob = analysisJobStore.findOwned(analysisId, userId);
        List<AnalysisImageResult> analysisImageResults = analysisJob.analysisImages().stream().map(this::findAnalysisImageResult).toList();
        return new AnalysisJobResult(analysisJob, analysisImageResults);
    }

    private AnalysisImageResult findAnalysisImageResult(AnalysisImage analysisImage) {
        if (analysisImage.aiAnalysisId() == null) {
            return AnalysisImageResult.from(analysisImage.objectKey(), AnalysisStatus.QUEUED, null, null);
        }
        try {
            AiImageAnalysisResponse aiImageAnalysisResponse = aiAnalysisClient.get(analysisImage.aiAnalysisId());
            return toAnalysisImageResult(analysisImage, aiImageAnalysisResponse);
        } catch (RestClientException | IllegalArgumentException runtimeException) {
            AnalysisExceptionCode analysisExceptionCode = AnalysisExceptionCode.LOOKUP_UNAVAILABLE;
            if (runtimeException instanceof RestClientResponseException restClientResponseException && restClientResponseException.getStatusCode().value() == 429) {
                analysisExceptionCode = AnalysisExceptionCode.LOOKUP_LIMITED;
            }
            CustomException customException = new CustomException(analysisExceptionCode);
            customException.initCause(runtimeException);
            throw customException;
        }
    }

    private AnalysisImageResult toAnalysisImageResult(AnalysisImage analysisImage, AiImageAnalysisResponse aiImageAnalysisResponse) {
        if (!analysisImage.aiAnalysisId().equals(aiImageAnalysisResponse.analysisId()) || aiImageAnalysisResponse.status() == null) {
            throw new RestClientException("AI 조회 응답이 올바르지 않습니다.");
        }
        AnalysisStatus analysisStatus = AnalysisStatus.valueOf(aiImageAnalysisResponse.status());
        if (analysisStatus == AnalysisStatus.PARTIALLY_COMPLETED
                || (analysisStatus == AnalysisStatus.COMPLETED && (aiImageAnalysisResponse.result() == null || aiImageAnalysisResponse.result().items() == null))
                || (analysisStatus == AnalysisStatus.FAILED && aiImageAnalysisResponse.error() == null)) {
            throw new RestClientException("AI 조회 응답이 올바르지 않습니다.");
        }
        if (analysisStatus == AnalysisStatus.COMPLETED) {
            return AnalysisImageResult.from(analysisImage.objectKey(), analysisStatus, aiImageAnalysisResponse.result(), null);
        }
        if (analysisStatus == AnalysisStatus.FAILED) {
            return AnalysisImageResult.from(analysisImage.objectKey(), analysisStatus, null, aiImageAnalysisResponse.error());
        }
        return AnalysisImageResult.from(analysisImage.objectKey(), analysisStatus, null, null);
    }
}
