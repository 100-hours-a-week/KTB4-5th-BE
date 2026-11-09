package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.recipe.application.RecommendationListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "추천")
public interface RecommendationListApi {
    @Operation(summary = "추천 목록 조회", description = "로그인 참여자의 현재 선택 목록을 반환합니다. 새 결과는 명시적 전환 전까지 표시하지 않습니다. "
            + "새 완료 결과 미전환 시 STALE, 최신 선택 시 READY입니다. 조회로 생성·전환·한도 차감은 발생하지 않습니다. "
            + "AI 추천이 부족하면 확인 후보를 뒤에 보충하고 원천·레시피 ID 중복을 제외해 최대 10개 중 limit개를 반환합니다. "
            + "recipeId는 SOURCE:sourceRecipeId이며 조리시간을 알 수 없으면 null입니다. "
            + "환경변수 KAFKA_ENV가 dev/local이면 dailyGeneration.limit·remaining은 차감 없이 2147483647이며 resetAt은 다음 서울 자정입니다. "
            + "그 외 값이나 미설정 시 기존 한도를 적용하며 Spring 프로필과 무관합니다.")
    @ApiResponse(responseCode = "200", description = "추천 레시피 조회 성공 (RECOMMEND-200-001)",
            content = @Content(examples = @ExampleObject(value = RecommendationListApiExamples.EMPTY)))
    @ApiResponse(responseCode = "400", description = "잘못된 경로·limit(1~10) (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)")
    @ApiResponse(responseCode = "403", description = "냉장고 접근 불가 (REFRIGERATOR-403-001)")
    @ApiResponse(responseCode = "404", description = "냉장고 없음 (REFRIGERATOR-404-001)")
    @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)")
    @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    ResponseEntity<SuccessResponse<RecommendationListResult>> get(@Parameter(hidden = true) Long userId, @Parameter(required = true) Long refrigeratorId,
            @Parameter(description = "최대 반환 개수 1~10, 기본 10. AI 추천 순서 후 확인 후보 순서 유지") int limit);
}
