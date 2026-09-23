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
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "재고", description = "냉장고 재고 조회 및 관리 API")
public interface IngredientApi {

    @Operation(
            summary = "재고 상세 조회",
            description = "재고 상세 정보와 수정·부분 비우기 요청에 사용할 strong ETag를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "재고 상세 조회 성공",
                    headers = @Header(name = "ETag", description = "수정·부분 비우기에 If-Match로 전달할 strong ETag",
                            schema = @Schema(type = "string"), example = "\"sha256-example-token\""),
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = DETAIL_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"GLOBAL-401-001","message":"로그인이 필요합니다."}
                            """))),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"REFRIGERATOR-403-001","message":"해당 냉장고에 접근할 수 없습니다."}
                            """))),
            @ApiResponse(responseCode = "404", description = "재고를 찾을 수 없음 (INGREDIENT-404-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-404-001","message":"재고를 찾을 수 없습니다."}
                            """))),
            @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"REFRIGERATOR-410-001","message":"삭제된 냉장고입니다."}
                            """))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"GLOBAL-500-001","message":"서버에서 요청을 처리하지 못했습니다."}
                            """)))
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
            description = "상세 조회에서 받은 strong ETag를 If-Match에 전달합니다. 제공된 필드만 수정하며 "
                    + "measureType, registrationSource, createdDate 및 이미지는 변경할 수 없습니다. "
                    + "측정 방식과 weightUnit은 기존 값으로 고정됩니다. 유통기한은 실제 변경 시 오늘부터 "
                    + "4년 이내여야 하며 기존 날짜를 유지할 수 있습니다. 성공 응답은 새 ETag를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "재고 수정 성공",
                    headers = @Header(name = "ETag", description = "다음 수정·부분 비우기에 사용할 새 strong ETag",
                            schema = @Schema(type = "string"), example = "\"sha256-example-token\""),
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = UPDATE_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 (INGREDIENT-400-003)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-400-003","message":"입력 형식이 잘못됐습니다."}
                            """))),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"GLOBAL-401-001","message":"로그인이 필요합니다."}
                            """))),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"REFRIGERATOR-403-001","message":"해당 냉장고에 접근할 수 없습니다."}
                            """))),
            @ApiResponse(responseCode = "404", description = "재고를 찾을 수 없음 (INGREDIENT-404-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-404-001","message":"재고를 찾을 수 없습니다."}
                            """))),
            @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)"),
            @ApiResponse(responseCode = "412", description = "ETag가 현재 버전과 다름 (INGREDIENT-412-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-412-001","message":"버전 정보가 맞지 않습니다."}
                            """))),
            @ApiResponse(responseCode = "422", description = "재고 수정 규칙 위반 (INGREDIENT-422-001~004)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-422-002","message":"수정할 재고 수량이 유효하지 않습니다."}
                            """))),
            @ApiResponse(responseCode = "428", description = "If-Match 헤더가 누락됨 (INGREDIENT-428-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-428-001","message":"현재 버전 정보를 If-Match 헤더에 전달해 주세요."}
                            """))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"GLOBAL-500-001","message":"서버에서 요청을 처리하지 못했습니다."}
                            """)))
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
                    description = "수정할 필드만 포함합니다. null은 미사용 측정 필드에만 허용됩니다.",
                    content = @Content(
                            schema = @Schema(implementation = IngredientUpdateRequest.class),
                            examples = @ExampleObject(name = "재고 수정 요청", value = UPDATE_REQUEST)
                    )
            )
            IngredientUpdateRequest request);

    @Operation(
            summary = "재고 비우기",
            description = "입력한 수량 또는 무게만큼 현재 활성 냉장고 재고에서 차감합니다. 잔량이 0이면 "
                    + "행을 삭제하고, 남으면 잔량을 반환합니다. 상세 조회의 strong ETag를 If-Match에 전달해야 합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "재고 만료 처리 성공",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EXPIRE_RESPONSE))
            ),
            @ApiResponse(responseCode = "204", description = "이미 삭제된 재고", content = @Content),
            @ApiResponse(responseCode = "400", description = "JSON/ETag 요청 형식 오류 (GLOBAL-400-001, INGREDIENT-400-003)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-400-003","message":"입력 형식이 잘못됐습니다."}
                            """))),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"GLOBAL-401-001","message":"로그인이 필요합니다."}
                            """))),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"REFRIGERATOR-403-001","message":"해당 냉장고에 접근할 수 없습니다."}
                            """))),
            @ApiResponse(responseCode = "404", description = "다른 냉장고의 재고 (INGREDIENT-404-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-404-001","message":"재고를 찾을 수 없습니다."}
                            """))),
            @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)"),
            @ApiResponse(responseCode = "412", description = "ETag가 현재 버전과 다름 (INGREDIENT-412-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-412-001","message":"버전 정보가 맞지 않습니다."}
                            """))),
            @ApiResponse(responseCode = "422", description = "처리 수량 또는 측정값 조합 오류 (INGREDIENT-422-005~006)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-422-005","message":"만료 처리할 재고 수량이 유효하지 않습니다."}
                            """))),
            @ApiResponse(responseCode = "428", description = "If-Match 헤더가 누락됨 (INGREDIENT-428-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-428-001","message":"현재 버전 정보를 If-Match 헤더에 전달해 주세요."}
                            """))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"GLOBAL-500-001","message":"서버에서 요청을 처리하지 못했습니다."}
                            """)))
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
            description = "냉장고에 재고를 1~20건 등록합니다. 동일한 이름·보관 방식·유통기한·측정 방식·단위의 "
                    + "기존 재고가 있으면 자동 합산하고 합산 내역을 응답합니다. 모든 항목 검증과 저장은 원자적이며, "
                    + "등록 가능한 유통기한은 오늘부터 4년 이내입니다."
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
            @ApiResponse(responseCode = "400", description = "JSON·측정 입력 오류 (GLOBAL-400-001, INGREDIENT-400-001~003)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-400-003","message":"입력 형식이 잘못됐습니다."}
                            """))),
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
            @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)"),
            @ApiResponse(responseCode = "409", description = "용량 초과 또는 합산 상한 초과 (INGREDIENT-409-001, INGREDIENT-409-004)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-409-001","message":"냉장고 용량이 가득 찼습니다."}
                            """))),
            @ApiResponse(responseCode = "422", description = "부적절한 재고 이름 (INGREDIENT-422-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"INGREDIENT-422-001","message":"부적절한 재고 이름입니다."}
                            """))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"GLOBAL-500-001","message":"서버에서 요청을 처리하지 못했습니다."}
                            """)))
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
                    description = "등록할 재고 1~20건. COUNT는 1~100, WEIGHT는 G/ML 기준 양의 정수입니다. "
                            + "동일한 기존 행은 자동 합산됩니다.",
                    content = @Content(
                            schema = @Schema(implementation = IngredientCreateRequest.class),
                            examples = @ExampleObject(name = "재고 일괄 등록 요청", value = CREATE_REQUEST)
                    )
            )
            IngredientCreateRequest request);
}
