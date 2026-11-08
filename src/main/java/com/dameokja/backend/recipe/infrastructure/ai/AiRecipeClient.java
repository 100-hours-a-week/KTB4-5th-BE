package com.dameokja.backend.recipe.infrastructure.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RequiredArgsConstructor
public class AiRecipeClient {
    private static final String RECOMMENDATIONS_PATH = "/ai/v1/recipe-recommendations";
    private final RestClient restClient;

    public AiRecipeSubmitResponse submit(AiRecipeRecommendationRequest request) {
        ResponseEntity<AiRecipeSubmitResponse> entity = restClient.post().uri(RECOMMENDATIONS_PATH).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().toEntity(AiRecipeSubmitResponse.class);
        AiRecipeSubmitResponse response = requireBody(entity, HttpStatus.ACCEPTED);
        response.validateFor(request);
        return response;
    }

    public AiRecipeJobResponse get(String jobId, AiRecipeRecommendationRequest request) {
        ResponseEntity<AiRecipeJobResponse> entity = restClient.get().uri(RECOMMENDATIONS_PATH + "/{jobId}", jobId)
                .retrieve().toEntity(AiRecipeJobResponse.class);
        AiRecipeJobResponse response = requireBody(entity, HttpStatus.OK);
        response.validateFor(jobId, request);
        return response;
    }

    private <T> T requireBody(ResponseEntity<T> entity, HttpStatus expectedStatus) {
        if (entity.getStatusCode() != expectedStatus || entity.getBody() == null) {
            throw new RestClientException("AI 추천 HTTP 상태 또는 응답 본문이 올바르지 않습니다.");
        }
        return entity.getBody();
    }
}
