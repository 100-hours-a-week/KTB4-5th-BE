package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.recipe.application.CookingApplicationService;
import com.dameokja.backend.recipe.application.RecommendationDetailResult;
import com.dameokja.backend.recipe.presentation.request.CookingApplyRequest;
import com.dameokja.backend.recipe.presentation.response.CookingApplyResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CookingApplyController implements CookingApplyApi {
    private final CookingApplicationService cookingApplicationService;

    @PostMapping(value = "/api/v1/refrigerators/{refrigeratorId}/cooking-records", consumes = "application/json")
    public ResponseEntity<SuccessResponse<CookingApplyResponse>> apply(@CurrentUserId Long userId, @PathVariable Long refrigeratorId,
            @Valid @RequestBody CookingApplyRequest request, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Optional<RecommendationDetailResult> result = cookingApplicationService.apply(userId, refrigeratorId, request.toCommand(ifMatch, idempotencyKey));
        if (result.isEmpty()) { return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
        CookingApplyResponse response = CookingApplyResponse.from(result.orElseThrow());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SuccessResponse.of("RECOMMEND-200-004", "추천 레시피 적용 성공", response));
    }
}
