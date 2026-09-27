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
            description = "로그인한 사용자가 현재 활성 상태로 속해 있는 냉장고 목록을 조회합니다."
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
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<List<RefrigeratorResponse>>> getActiveRefrigerators(
            @Parameter(hidden = true) Long userId);
}
