package com.dameokja.backend.ingredient.presentation;

import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.CREATE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.CREATE_RESPONSE;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.DETAIL_RESPONSE;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.EXPIRE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.EXPIRE_RESPONSE;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.UPDATE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.UPDATE_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.ingredient.presentation.request.IngredientCreateRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientExpireRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientUpdateRequest;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientExpireResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientResponse;
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

@Tag(name = "재고", description = "냉장고 재고 API")
public interface IngredientApi {

    @Operation(
            summary = "재고 상세 조회",
            description = "재고 상세 정보와 수정 요청에 사용할 현재 버전 ETag를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "재고 상세 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = DETAIL_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)"),
            @ApiResponse(responseCode = "404", description = "재고를 찾을 수 없음 (INGREDIENT-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<IngredientResponse>> getDetail(
            @Parameter(hidden = true) Long userId,
            @Parameter(
                    name = "ingredientId",
                    in = ParameterIn.PATH,
                    description = "조회할 재고 ID",
                    required = true,
                    example = "1"
            )
            Long ingredientId);

    @Operation(
            summary = "재고 수정",
            description = "상세 조회에서 받은 ETag를 사용해 재고의 제공된 필드만 수정합니다. "
                    + "측정 방식, 등록 방식, 등록일과 이미지는 변경할 수 없습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "재고 수정 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = UPDATE_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 (INGREDIENT-400-003)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)"),
            @ApiResponse(responseCode = "404", description = "재고를 찾을 수 없음 (INGREDIENT-404-001)"),
            @ApiResponse(responseCode = "412", description = "ETag가 현재 버전과 다름 (INGREDIENT-412-001)"),
            @ApiResponse(responseCode = "422", description = "재고 수정 규칙 위반 (INGREDIENT-422-001~004)"),
            @ApiResponse(responseCode = "428", description = "If-Match 헤더가 누락됨 (INGREDIENT-428-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<IngredientResponse>> update(
            @Parameter(hidden = true) Long userId,
            @Parameter(
                    name = "ingredientId",
                    in = ParameterIn.PATH,
                    description = "수정할 재고 ID",
                    required = true,
                    example = "1"
            )
            Long ingredientId,
            @Parameter(
                    name = "If-Match",
                    in = ParameterIn.HEADER,
                    description = "재고 상세 조회에서 받은 strong ETag",
                    required = true
            )
            String ifMatch,
            @RequestBody(
                    required = true,
                    description = "수정할 필드만 포함합니다.",
                    content = @Content(
                            schema = @Schema(implementation = IngredientUpdateRequest.class),
                            examples = @ExampleObject(name = "재고 수정 요청", value = UPDATE_REQUEST)
                    )
            )
            IngredientUpdateRequest request);

    @Operation(
            summary = "재고 만료 처리",
            description = "입력한 수량만큼 재고에서 차감하고, 잔량이 0이면 재고를 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "재고 만료 처리 성공",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EXPIRE_RESPONSE))
            ),
            @ApiResponse(responseCode = "204", description = "이미 삭제된 재고"),
            @ApiResponse(responseCode = "400", description = "JSON 요청 형식 오류 (GLOBAL-400-001)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)"),
            @ApiResponse(responseCode = "404", description = "다른 냉장고의 재고 (INGREDIENT-404-001)"),
            @ApiResponse(responseCode = "412", description = "ETag가 현재 버전과 다름 (INGREDIENT-412-001)"),
            @ApiResponse(responseCode = "422", description = "처리 수량 또는 측정값 조합이 유효하지 않음 (INGREDIENT-422-005~006)"),
            @ApiResponse(responseCode = "428", description = "If-Match 헤더가 누락됨 (INGREDIENT-428-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<IngredientExpireResponse>> expire(
            @Parameter(hidden = true) Long userId,
            @Parameter(name = "ingredientId", in = ParameterIn.PATH, description = "만료 처리할 재고 ID", required = true, example = "1")
            Long ingredientId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, description = "재고 상세 조회에서 받은 strong ETag", required = true)
            String ifMatch,
            @RequestBody(
                    required = true,
                    description = "처리할 개수 또는 무게 중 하나만 전달합니다.",
                    content = @Content(
                            schema = @Schema(implementation = IngredientExpireRequest.class),
                            examples = @ExampleObject(name = "재고 만료 처리 요청", value = EXPIRE_REQUEST)
                    )
            )
            IngredientExpireRequest request);

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
