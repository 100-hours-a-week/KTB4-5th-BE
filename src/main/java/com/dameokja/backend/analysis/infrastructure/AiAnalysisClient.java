package com.dameokja.backend.analysis.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RequiredArgsConstructor
public class AiAnalysisClient {
    private static final String ANALYSES_PATH = "/ai/v1/analyses";
    private final RestClient restClient;

    public AiAnalysisAccepted submit(AiAnalysisRequest request) {
        AiAnalysisAccepted response = restClient.post().uri(ANALYSES_PATH).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(AiAnalysisAccepted.class);
        return requireBody(response);
    }

    public AiAnalysisView get(String analysisId) {
        AiAnalysisView response = restClient.get().uri(ANALYSES_PATH + "/{analysisId}", analysisId)
                .retrieve().body(AiAnalysisView.class);
        return requireBody(response);
    }

    private <T> T requireBody(T response) {
        if (response == null) {
            throw new RestClientException("AI API 응답 본문이 없습니다.");
        }
        return response;
    }
}
