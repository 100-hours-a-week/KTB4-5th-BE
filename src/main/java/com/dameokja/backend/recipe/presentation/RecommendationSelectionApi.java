package com.dameokja.backend.recipe.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "추천")
public interface RecommendationSelectionApi {
    @Operation(summary = "조회 추천 결과 전환", description = "로그인한 참여자의 최신 완료 결과를 현재 선택 목록으로 지정합니다. "
            + "요청 본문·추천 ID는 전달하지 않습니다. 새 결과가 없거나 이미 최신이어도 현재 상태를 유지하며 204를 반환합니다. "
            + "새 생성·일일 한도 차감은 없으며 accessToken·XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더가 필요합니다.")
    @ApiResponse(responseCode = "204", description = "전환 처리 완료 또는 현재 상태 유지. 본문 없음")
    @ApiResponse(responseCode = "400", description = "잘못된 경로 변수 (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)")
    @ApiResponse(responseCode = "403", description = "냉장고 접근 불가 (REFRIGERATOR-403-001), CSRF 오류 (COMMON-403-CSRF-001)")
    @ApiResponse(responseCode = "404", description = "냉장고 없음 (REFRIGERATOR-404-001)")
    @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)")
    @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    ResponseEntity<Void> selectLatest(@Parameter(hidden = true) Long userId, @Parameter(required = true) Long refrigeratorId);
}
