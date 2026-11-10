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
            + "현재 목록은 유지하며 기본적으로 같은 재고·취향·옵션도 완료 이력을 재사용하지 않고 새 AI 작업으로 접수합니다. "
            + "당일 요청이 생성 중이거나 미전환 결과가 있으면 거절하며 전날 요청은 차단하지 않습니다. 사용자별 하루 10회입니다. "
            + "RECOMMENDATION_REUSE_ENABLED=true일 때만 기존 완료 이력을 재사용하며, 재사용 시 한도를 차감하지 않습니다. "
            + "환경변수 KAFKA_ENV가 dev/local이면 횟수 제한·차감이 없으며 그 외 값이나 미설정 시 10회 제한을 적용합니다. Spring 프로필과 무관합니다. "
            + "무제한일 때 dailyGeneration.limit·remaining은 2147483647이며 resetAt은 기존처럼 다음 서울 자정입니다. "
            + "매일 서울 시간 7시에는 현재 활성 냉장고를 마지막 취향(없으면 기본값)으로 자동 갱신합니다. 공유 참여 중 개인 냉장고는 제외합니다. "
            + "자동 요청은 미전환 최신 결과를 갱신할 수 있으며 현재 선택을 유지하고 사용자 한도를 차감하지 않습니다. 생성 종료 SSE의 generationTrigger는 SCHEDULED입니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = RecommendationJobSubmitRequest.class),
            examples = @ExampleObject(value = RecommendationJobApiExamples.REQUEST)))
    @ApiResponse(responseCode = "202", description = "추천 생성 시작 (RECOMMEND-202-001)", content = @Content(examples = @ExampleObject(value = RecommendationJobApiExamples.ACCEPTED)))
    @ApiResponse(responseCode = "200", description = "재사용 활성화 시 기존 결과 재사용 (RECOMMEND-200-003)", content = @Content(examples = @ExampleObject(value = RecommendationJobApiExamples.CURRENT)))
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
