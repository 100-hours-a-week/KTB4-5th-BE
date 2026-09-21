package com.dameokja.backend.ingredient.presentation;

import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.CREATE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.CREATE_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.ingredient.presentation.request.IngredientCreateRequest;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "재고 등록", description = "냉장고 재고 등록 API")
public interface IngredientApi {

    @Operation(
            summary = "재고 일괄 등록",
            description = "냉장고에 재고를 1~20건 등록합니다. 동일한 기존 재고가 있으면 별도 확인 요청 없이 자동 합산하고, 합산 내역을 응답합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "재고 일괄등록 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = CREATE_RESPONSE)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 형식 또는 재고 입력값 오류 "
                            + "(GLOBAL-400-001, INGREDIENT-400-001~003)"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "로그인이 필요함 (GLOBAL-401-001)"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "냉장고를 찾을 수 없음 (REFRIGERATOR-404-001)"
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "용량 초과 또는 합산 상한 초과 (INGREDIENT-409-001, INGREDIENT-409-004)"
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "부적절한 재고 이름 (INGREDIENT-422-001)"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류 (GLOBAL-500-001)"
            )
    })
    ResponseEntity<SuccessResponse<IngredientCreateResponse>> create(
            @Parameter(hidden = true) Long userId,
            @Parameter(
                    name = "refrigeratorId",
                    in = ParameterIn.PATH,
                    description = "재고를 등록할 냉장고 ID",
                    required = true,
                    example = "1"
            )
            Long refrigeratorId,
            @RequestBody(
                    required = true,
                    description = "등록할 재고 1~20건. WEIGHT 입력은 G/ML 기준 양의 정수만 허용합니다.",
                    content = @Content(
                            schema = @Schema(implementation = IngredientCreateRequest.class),
                            examples = @ExampleObject(name = "재고 일괄 등록 요청", value = CREATE_REQUEST)
                    )
            )
            IngredientCreateRequest request);
}
