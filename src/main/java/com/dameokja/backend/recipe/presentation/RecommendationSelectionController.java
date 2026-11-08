package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.recipe.application.RecommendationSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RecommendationSelectionController implements RecommendationSelectionApi {
    private final RecommendationSelectionService recommendationSelectionService;

    @PostMapping("/api/v1/refrigerators/{refrigeratorId}/recommendations/current")
    public ResponseEntity<Void> selectLatest(@CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        recommendationSelectionService.selectLatest(userId, refrigeratorId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
