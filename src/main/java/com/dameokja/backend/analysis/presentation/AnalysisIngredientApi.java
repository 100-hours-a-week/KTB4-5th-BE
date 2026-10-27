package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.application.registration.AnalysisIngredientCommand;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "이미지 분석")
public interface AnalysisIngredientApi {
    @Operation(summary = "사진 인식 결과 일괄 등록", description = "accessToken·XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더가 필요합니다. "
            + "본인의 보관 중인 작업에서 COMPLETED이고 OTHER가 아닌 이미지의 원본 항목을 선택해 1~20개 등록합니다. "
            + "itemId는 공백이 아닌 최대 64자이며 보관된 AI analysisId도 최대 64자여야 합니다. 길이가 잘못된 기존 결과는 등록하지 않습니다. "
            + "COUNT는 quantity 정수 1~100·weight null, WEIGHT는 quantity null 또는 0·weight.value 정수 1~50000·unit G/ML입니다. "
            + "expiration은 서울 기준 등록일~4년 후입니다. RECEIPT는 RECEIPT, PRODUCT는 FOOD_IMAGE로 기록합니다. 인증된 회원을 등록자로 저장하고 같은 등록자의 재고끼리 자동 합산합니다. "
            + "피드백의 확정 이름은 신규 등록·합산 후 실제 저장된 재고명을 사용합니다. "
            + "등록 시 AI 분석 ID별로 refrigeratorId·items[]를 포함한 피드백을 재고와 같은 트랜잭션으로 큐에 저장합니다. 각 항목은 CORRECTED·동의 true·expirationSource=USER_CONFIRMED입니다. "
            + "큐 저장 실패는 재고 등록도 롤백합니다. 커밋 후 선택하지 않은 항목을 포함한 묶음 전체를 삭제하고 201을 반환합니다. AI 전송 실패는 큐에서 재시도합니다. "
            + "삭제 실패도 201을 유지하며 로그에 기록합니다. 응답 유실 시 재고 조회로 확인하고 자동 재등록하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "재고 일괄등록 성공 (INGREDIENT-201-001)", content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = AnalysisIngredientApiExamples.RESPONSE))),
            @ApiResponse(responseCode = "400", description = "형식 오류 (GLOBAL-400-001), 분석 연결 오류 (IMAGE-400-002), 항목 오류 (INGREDIENT-400-001~003)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)"),
            @ApiResponse(responseCode = "403", description = "다른 사용자 작업 (GLOBAL-403-001), 냉장고 권한 (REFRIGERATOR-403-001), CSRF (COMMON-403-CSRF-001)"),
            @ApiResponse(responseCode = "404", description = "작업 없음·만료 (IMAGE-404-001), 냉장고 없음 (REFRIGERATOR-404-001)"),
            @ApiResponse(responseCode = "409", description = "용량 초과 (INGREDIENT-409-001), 합산 상한 (INGREDIENT-409-004)"),
            @ApiResponse(responseCode = "422", description = "금칙어 (INGREDIENT-422-001)"),
            @ApiResponse(responseCode = "500", description = "서버 처리 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<IngredientCreateResponse>> create(@Parameter(hidden = true) Long userId,
            @Parameter(description = "냉장고 ID", required = true) Long refrigeratorId,
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnalysisIngredientCommand.class),
                    examples = @ExampleObject(value = AnalysisIngredientApiExamples.REQUEST))) AnalysisIngredientCommand request);
}
