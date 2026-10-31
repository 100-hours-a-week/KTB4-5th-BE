package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.recipe.presentation.request.CookingApplyRequest;
import com.dameokja.backend.recipe.presentation.response.CookingApplyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "추천")
public interface CookingApplyApi {
    @Operation(summary = "추천 레시피 요리 적용", description = "accessToken·XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더가 필요합니다. "
            + "상세에서 확인한 내역과 현재 응답이 같을 때만 적용합니다. 변경 시 412이며 상세 재조회·재확인 후 새 키로 요청합니다. "
            + "동일 요청의 성공 재시도는 현재 재고와 무관하게 204입니다. 사용량 계산 불가 재고는 차감하지 않습니다.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = CookingApplyRequest.class),
            examples = @ExampleObject(value = CookingApplyApiExamples.REQUEST)))
    @ApiResponse(responseCode = "200", description = "최초 적용 성공 (RECOMMEND-200-004), 실제 차감한 재고별 합산 내역",
            content = @Content(examples = @ExampleObject(value = CookingApplyApiExamples.APPLIED)))
    @ApiResponse(responseCode = "204", description = "이미 성공한 동일 요청, 응답 본문 없음")
    @ApiResponse(responseCode = "400", description = "잘못된 본문·경로·필수 헤더 누락 또는 형식 오류 (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)")
    @ApiResponse(responseCode = "403", description = "냉장고 접근 불가 (REFRIGERATOR-403-001), CSRF 오류 (COMMON-403-CSRF-001)")
    @ApiResponse(responseCode = "404", description = "냉장고 없음 (REFRIGERATOR-404-001), 본인 추천·레시피 없음 (RECOMMEND-404-001)")
    @ApiResponse(responseCode = "409", description = "같은 멱등 키로 요청 내용 변경 (RECOMMEND-409-001)")
    @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)")
    @ApiResponse(responseCode = "412", description = "확인한 상세 변경, 차감·기록·횟수 변경 없음 (RECOMMEND-412-001)")
    @ApiResponse(responseCode = "415", description = "JSON이 아닌 요청 형식 (GLOBAL-415-001)")
    @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    ResponseEntity<SuccessResponse<CookingApplyResponse>> apply(@Parameter(hidden = true) Long userId, @Parameter(required = true) Long refrigeratorId,
            CookingApplyRequest request,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true, description = "상세 ETag 원문: 따옴표로 감싼 소문자 16진수 64자리") String ifMatch,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "1~64자, 공백만으로 구성 불가. 동일 요청 재시도 시 유지") String idempotencyKey);
}
