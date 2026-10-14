package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.presentation.request.AnalysisCallbackRequest;
import com.dameokja.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;

@Tag(name = "분석 결과 콜백")
public interface AnalysisCallbackApi {
    @Operation(summary = "이미지별 종료 결과 수신", description = "X-Internal-API-Key 인증이 필요합니다. COMPLETED·FAILED만 수신합니다. "
            + "RECEIPT·PRODUCT·OTHER 결과와 reviewReasons 객체 배열을 보존하며 같은 결과의 재전송은 중복 반영하지 않습니다.",
            parameters = @Parameter(name = "X-Internal-API-Key", in = ParameterIn.HEADER, required = true))
    @ApiResponse(responseCode = "200", description = "분석 결과 수신 (IMAGE-200-003)")
    @ApiResponse(responseCode = "400", description = "필드 조합 오류 (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "서비스 인증 실패 (IMAGE-401-001)")
    @ApiResponse(responseCode = "409", description = "기존 결과와 충돌 (IMAGE-409-001)")
    @ApiResponse(responseCode = "503", description = "재전송 필요 (IMAGE-503-003, retryable=true)")
    ResponseEntity<SuccessResponse<Map<String, String>>> receive(
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnalysisCallbackRequest.class))) AnalysisCallbackRequest request);
}
