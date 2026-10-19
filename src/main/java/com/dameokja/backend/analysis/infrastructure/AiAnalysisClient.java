package com.dameokja.backend.analysis.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RequiredArgsConstructor
public class AiAnalysisClient {
    private static final String ANALYSES_PATH = "/ai/v1/analyses";
    private static final String READY_PATH = "/health/ready";
    private final RestClient restClient;

    public AiImageAnalysisSubmitResponse submit(AiImageAnalysisSubmitRequest aiImageAnalysisSubmitRequest) {
        AiImageAnalysisSubmitResponse aiImageAnalysisSubmitResponse = restClient.post().uri(ANALYSES_PATH).contentType(MediaType.APPLICATION_JSON)
                .body(aiImageAnalysisSubmitRequest).retrieve().body(AiImageAnalysisSubmitResponse.class);
        return requireBody(aiImageAnalysisSubmitResponse);
    }

    public AiImageAnalysisResponse get(String analysisId) {
        AiImageAnalysisResponse aiImageAnalysisResponse = restClient.get().uri(ANALYSES_PATH + "/{analysisId}", analysisId)
                .retrieve().body(AiImageAnalysisResponse.class);
        return requireBody(aiImageAnalysisResponse);
    }

    public AiAnalysisFeedbackResponse feedback(String analysisId, AiAnalysisFeedbackRequest aiAnalysisFeedbackRequest) {
        AiAnalysisFeedbackResponse aiAnalysisFeedbackResponse = restClient.post().uri(ANALYSES_PATH + "/{analysisId}/feedback", analysisId)
                .contentType(MediaType.APPLICATION_JSON).body(aiAnalysisFeedbackRequest).retrieve().body(AiAnalysisFeedbackResponse.class);
        return requireBody(aiAnalysisFeedbackResponse);
    }

    // 준비되지 않은 AI에 보내면 전송 시도만 소모하므로, 판단할 수 없는 응답도 준비되지 않은 것으로 본다.
    public boolean ready() {
        try {
            return restClient.get().uri(READY_PATH).retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
        } catch (RestClientException exception) {
            return false;
        }
    }

    private <T> T requireBody(T response) {
        if (response == null) {
            throw new RestClientException("AI API 응답 본문이 없습니다.");
        }
        return response;
    }
}
