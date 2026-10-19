package com.dameokja.backend.refrigerator.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.refrigerator.presentation.response.MonthlyExpirationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "재고", description = "재고 API")
public interface MonthlyExpirationApi {
    @Operation(summary = "이번 달 만료 재고 수 조회", description = "현재 선택한 냉장고에서 이번 달에 만료된 재고 행 수입니다. "
            + "유통기한 다음 날 한국 시간 00시부터 집계하며, 수량 합계가 아닙니다. 삭제 후에도 유지하고 월이 바뀌면 새로 집계합니다. "
            + "GET은 DB를 변경하지 않으며 배치 미반영분을 포함합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이번 달 만료된 재고 목록 조회 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = RefrigeratorApiExamples.MONTHLY_EXPIRATION_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "로그인 필요 또는 토큰 오류"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")
    })
    ResponseEntity<SuccessResponse<MonthlyExpirationResponse>> getCount(
            @Parameter(hidden = true) Long userId, Long refrigeratorId);
}
