package com.dameokja.backend.recipe.infrastructure.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RequiredArgsConstructor
public class AiRecipeClient {
    private static final String RECOMMENDATIONS_PATH = "/ai/v1/recipe-recommendations";
    private final RestClient restClient;

    public AiRecipeRecommendationResponse recommend(AiRecipeRecommendationRequest request) {
        AiRecipeRecommendationResponse response = restClient.post().uri(RECOMMENDATIONS_PATH).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(AiRecipeRecommendationResponse.class);
        if (response == null) {
            throw new RestClientException("AI 추천 응답 본문이 없습니다.");
        }
        response.validateFor(request);
        return response;
    }
}
