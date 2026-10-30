package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.recipe.application.RecommendationDetailResult;
import com.dameokja.backend.recipe.application.RecommendationDetailService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequiredArgsConstructor
public class RecommendationDetailController implements RecommendationDetailApi {
    private final RecommendationDetailService recommendationDetailService;
    private final JsonMapper jsonMapper;

    @GetMapping("/api/v1/refrigerators/{refrigeratorId}/recipes/{recipeId}")
    public ResponseEntity<SuccessResponse<RecommendationDetailResult>> get(@CurrentUserId Long userId, @PathVariable Long refrigeratorId, @PathVariable String recipeId) {
        RecommendationDetailResult recipe = recommendationDetailService.get(userId, refrigeratorId, recipeId);
        SuccessResponse<RecommendationDetailResult> response = SuccessResponse.of("RECOMMEND-200-002", "레시피 상세 조회 성공", recipe);
        String entityTag = entityTag(response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(entityTag).body(response);
    }

    private String entityTag(SuccessResponse<RecommendationDetailResult> response) {
        try {
            byte[] responseBytes = jsonMapper.writeValueAsBytes(response);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(responseBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
