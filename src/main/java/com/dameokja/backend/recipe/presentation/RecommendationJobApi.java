package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.recipe.presentation.request.RecommendationJobSubmitRequest;
import com.dameokja.backend.recipe.presentation.response.RecommendationJobSubmitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "추천")
public interface RecommendationJobApi {
    @Operation(summary = "추천 생성 요청", description = "accessToken 및 XSRF-TOKEN 쿠키·X-XSRF-TOKEN 헤더가 필요합니다. {}는 기본값을 사용합니다. "
            + "현재 목록은 유지하며 동일 재고·옵션의 선택 결과는 재사용합니다. 생성 중이거나 미전환 결과가 있으면 거절합니다. 사용자별 하루 10회입니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = RecommendationJobSubmitRequest.class),
            examples = @ExampleObject(value = RecommendationJobApiExamples.REQUEST)))
    @ApiResponse(responseCode = "202", description = "추천 생성 시작 (RECOMMEND-202-001)", content = @Content(examples = @ExampleObject(value = RecommendationJobApiExamples.ACCEPTED)))
    @ApiResponse(responseCode = "200", description = "이미 최신 추천 (RECOMMEND-200-003)", content = @Content(examples = @ExampleObject(value = RecommendationJobApiExamples.CURRENT)))
    @ApiResponse(responseCode = "400", description = "잘못된 입력 (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)")
    @ApiResponse(responseCode = "403", description = "냉장고 접근 불가 (REFRIGERATOR-403-001), CSRF 오류 (COMMON-403-CSRF-001)")
    @ApiResponse(responseCode = "404", description = "냉장고 없음 (REFRIGERATOR-404-001)")
    @ApiResponse(responseCode = "409", description = "생성 중 (RECOMMEND-409-003), 미전환 결과 존재 (RECOMMEND-409-004)")
    @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)")
    @ApiResponse(responseCode = "429", description = "일일 한도 초과 (RECOMMEND-429-001)")
    @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    ResponseEntity<SuccessResponse<RecommendationJobSubmitResponse>> submit(@Parameter(hidden = true) Long userId,
            @Parameter(required = true) Long refrigeratorId, RecommendationJobSubmitRequest recommendationJobSubmitRequest);
}
