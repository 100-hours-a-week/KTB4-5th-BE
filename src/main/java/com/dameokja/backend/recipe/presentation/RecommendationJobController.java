package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.recipe.application.RecommendationSubmissionResult;
import com.dameokja.backend.recipe.application.RecommendationSubmissionService;
import com.dameokja.backend.recipe.presentation.request.RecommendationJobSubmitRequest;
import com.dameokja.backend.recipe.presentation.response.RecommendationJobSubmitResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RecommendationJobController implements RecommendationJobApi {
    private final RecommendationSubmissionService recommendationSubmissionService;

    @PostMapping("/api/v1/refrigerators/{refrigeratorId}/recommendation-jobs")
    public ResponseEntity<SuccessResponse<RecommendationJobSubmitResponse>> submit(@CurrentUserId Long userId, @PathVariable Long refrigeratorId,
            @Valid @RequestBody RecommendationJobSubmitRequest recommendationJobSubmitRequest) {
        RecommendationSubmissionResult recommendationSubmissionResult = recommendationSubmissionService.submit(userId, refrigeratorId, recommendationJobSubmitRequest.toOptions());
        boolean alreadyUpToDate = recommendationSubmissionResult.recommendationId() != null;
        return ResponseEntity.status(alreadyUpToDate ? HttpStatus.OK : HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(alreadyUpToDate ? "RECOMMEND-200-003" : "RECOMMEND-202-001", alreadyUpToDate ? "이미 최신 추천입니다." : "추천 생성 시작",
                        RecommendationJobSubmitResponse.from(recommendationSubmissionResult, recommendationJobSubmitRequest.mode())));
    }
}
