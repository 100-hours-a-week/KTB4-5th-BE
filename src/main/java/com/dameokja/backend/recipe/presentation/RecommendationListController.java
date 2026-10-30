package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.recipe.application.RecommendationListResult;
import com.dameokja.backend.recipe.application.RecommendationListService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RecommendationListController implements RecommendationListApi {
    private final RecommendationListService recommendationListService;

    @GetMapping("/api/v1/refrigerators/{refrigeratorId}/recommendations")
    public ResponseEntity<SuccessResponse<RecommendationListResult>> get(@CurrentUserId Long userId, @PathVariable Long refrigeratorId,
            @RequestParam(defaultValue = "10") int limit) {
        RecommendationListResult recommendationList = recommendationListService.get(userId, refrigeratorId, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of("RECOMMEND-200-001", "추천 레시피 조회 성공", recommendationList));
    }
}
