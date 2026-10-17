package com.dameokja.backend.refrigerator.presentation;

import static com.dameokja.backend.refrigerator.presentation.RefrigeratorApiExamples.ACTIVE_LIST_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.refrigerator.presentation.response.RefrigeratorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;

@Tag(name = "냉장고", description = "냉장고 API")
public interface RefrigeratorApi {

    @Operation(
            summary = "활성 냉장고 목록 조회",
            description = "Query와 Body 없이 로그인한 사용자가 현재 선택한 유효 냉장고 목록을 조회합니다. "
                    + "expiredCount는 이번 달 만료된 재고 행 누적 수이며 삭제 후에도 유지됩니다. 배치 미반영분을 포함하고 월이 바뀌면 새로 집계합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "활성 냉장고 목록 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = ACTIVE_LIST_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<List<RefrigeratorResponse>>> getActiveRefrigerators(
            @Parameter(hidden = true) Long userId);
}
