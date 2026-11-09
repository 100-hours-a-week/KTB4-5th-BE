package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.recipe.application.RecommendationDetailResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "추천")
public interface RecommendationDetailApi {
    @Operation(summary = "레시피 상세 조회", description = "본인의 현재 선택 결과에서 레시피를 조회합니다. 재료 ID는 이 레시피의 재료 번호 문자열입니다. "
            + "목록 부족분으로 보충된 확인 후보도 같은 최대 10개 목록에서 조회합니다. "
            + "stocks는 AI usedAmount를 현재 재고 이내로 제한한 예상 사용량입니다. AI 사용량이 없으면 requiredAmount로 계산하며 인분을 추가로 곱하지 않습니다. "
            + "표시용 requiredAmount가 없으면 원본 amountValue × targetServings를 반환하며, 둘 중 하나라도 없으면 null입니다. "
            + "계산 불가는 usedAmount=null, UNRESOLVED입니다. 조회로 재고를 차감하지 않습니다. ETag는 응답 내용이 같으면 유지됩니다.")
    @ApiResponse(responseCode = "200", description = "레시피 상세 조회 성공 (RECOMMEND-200-002)", content = @Content(examples = @ExampleObject(value = RecommendationDetailApiExamples.DETAIL)))
    @ApiResponse(responseCode = "304", description = "If-None-Match가 현재 ETag와 일치하면 응답 본문 없음")
    @ApiResponse(responseCode = "400", description = "잘못된 경로·원천 조합 식별자 (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)")
    @ApiResponse(responseCode = "403", description = "냉장고 접근 불가 (REFRIGERATOR-403-001)")
    @ApiResponse(responseCode = "404", description = "냉장고 없음 (REFRIGERATOR-404-001), 선택 결과·레시피 없음 (RECOMMEND-404-001)")
    @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)")
    @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    ResponseEntity<SuccessResponse<RecommendationDetailResult>> get(@Parameter(hidden = true) Long userId, @Parameter(required = true) Long refrigeratorId,
            @Parameter(required = true, description = "목록에서 받은 SOURCE:sourceRecipeId 문자열") String recipeId);
}
