package com.dameokja.backend.recipe.presentation;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ResponseEntity;

@Tag(name = "추천 결과 콜백")
public interface RecommendationCallbackApi {
    @Operation(summary = "추천 작업 종료 결과 수신", description = "AI GET의 최종 작업 본문을 수신합니다. COMPLETED·FAILED만 허용하며 "
            + "jobId·requestId·냉장고·재고 지문·모드를 대조합니다. 중복 종료 결과는 보관기간을 연장하지 않습니다.",
            parameters = @Parameter(name = "X-Internal-API-Key", in = ParameterIn.HEADER, required = true))
    @ApiResponse(responseCode = "204", description = "종료 결과 수신, 본문 없음")
    @ApiResponse(responseCode = "400", description = "필드·상태·요청 불일치 (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "내부 키 인증 실패 (RECOMMEND-401-001)")
    @ApiResponse(responseCode = "404", description = "알 수 없는 요청 (GLOBAL-404-001)")
    @ApiResponse(responseCode = "503", description = "접수 정보 저장 전이거나 저장소 장애. 본문 없음, 재전송 필요")
    ResponseEntity<Void> receive(@RequestBody(required = true, content = @Content(schema = @Schema(implementation = AiRecipeJobResponse.class))) AiRecipeJobResponse aiRecipeJobResponse);
}
