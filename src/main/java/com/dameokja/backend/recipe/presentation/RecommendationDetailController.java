package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.recipe.application.RecommendationDetailEtag;
import com.dameokja.backend.recipe.application.RecommendationDetailResult;
import com.dameokja.backend.recipe.application.RecommendationDetailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RecommendationDetailController implements RecommendationDetailApi {
    private final RecommendationDetailService recommendationDetailService;
    private final RecommendationDetailEtag recommendationDetailEtag;

    @GetMapping("/api/v1/refrigerators/{refrigeratorId}/recipes/{recipeId}")
    public ResponseEntity<SuccessResponse<RecommendationDetailResult>> get(@CurrentUserId Long userId, @PathVariable Long refrigeratorId, @PathVariable String recipeId) {
        RecommendationDetailResult recipe = recommendationDetailService.get(userId, refrigeratorId, recipeId);
        SuccessResponse<RecommendationDetailResult> response = recipe.toResponse();
        String entityTag = recommendationDetailEtag.of(recipe);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(entityTag).body(response);
    }
}
