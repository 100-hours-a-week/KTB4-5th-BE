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
            + "RECEIPT·PRODUCT·OTHER 결과와 reviewReasons 객체 배열을 보존하며 같은 결과의 재전송은 중복 반영하지 않습니다. "
            + "수량은 null 또는 0~100, 중량은 null 또는 0 이상을 허용합니다. 측정 방식·수량·중량·단위의 조합은 재고 등록에서 검증하며, 콜백에서는 둘 다 0인 결과도 단위와 관계없이 보존합니다. "
            + "각 이미지 종료마다 SSE 개별 완료 이벤트를 발행합니다. 마지막 이미지 종료 시에는 SSE 연결이 있으면 전체 완료 이벤트도 발행하고, 없으면 푸시를 생성합니다. 알림 처리 실패는 503으로 재전송을 요청합니다.",
            parameters = @Parameter(name = "X-Internal-API-Key", in = ParameterIn.HEADER, required = true))
    @ApiResponse(responseCode = "200", description = "분석 결과 수신 (IMAGE-200-003)")
    @ApiResponse(responseCode = "400", description = "필드 조합 오류 (GLOBAL-400-001)")
    @ApiResponse(responseCode = "401", description = "서비스 인증 실패 (IMAGE-401-001)")
    @ApiResponse(responseCode = "409", description = "기존 결과와 충돌 (IMAGE-409-001)")
    @ApiResponse(responseCode = "503", description = "재전송 필요 (IMAGE-503-003, retryable=true)")
    ResponseEntity<SuccessResponse<Map<String, String>>> receive(
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnalysisCallbackRequest.class))) AnalysisCallbackRequest request);
}
