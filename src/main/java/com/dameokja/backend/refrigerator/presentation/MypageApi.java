package com.dameokja.backend.refrigerator.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.refrigerator.presentation.response.MypageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "마이페이지")
public interface MypageApi {
    @Operation(summary = "마이페이지 통합 조회", description = "현재 선택한 냉장고의 유효 구성원만 조회합니다. "
            + "프로필·보유 재고 행 수·삭제 후에도 유지하는 월별 만료 누적·본인 포함 구성원 수·유형별 알림 설정을 반환합니다. "
            + "카카오 이메일 미제공 시 null이며 이미지 URL은 임시 주소입니다. 브라우저 권한·알림 배지는 포함하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "REFRIGERATOR-200-007. Cache-Control: no-store",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = MypageApiExamples.OVERVIEW))),
            @ApiResponse(responseCode = "400", description = "GLOBAL-400-001"),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<SuccessResponse<MypageResponse>> getOverview(@Parameter(hidden = true) Long userId,
            @Parameter(description = "현재 선택한 냉장고 ID", example = "17") Long refrigeratorId);
}
