package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.recipe.application.RecommendationCallbackService;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequiredArgsConstructor
public class RecommendationCallbackController implements RecommendationCallbackApi {
    private final RecommendationCallbackService recommendationCallbackService;

    @PostMapping("/api/v1/internal/recipe-recommendations/callback")
    public ResponseEntity<Void> receive(@RequestBody AiRecipeJobResponse aiRecipeJobResponse) {
        try {
            recommendationCallbackService.receive(aiRecipeJobResponse);
        } catch (IllegalArgumentException | RestClientException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        } catch (NoSuchElementException exception) {
            throw new CustomException(GlobalExceptionCode.NOT_FOUND);
        } catch (IllegalStateException | DataAccessException exception) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
