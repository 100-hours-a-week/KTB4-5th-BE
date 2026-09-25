package com.dameokja.backend.ingredient.presentation;

import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.CREATE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.CREATE_RESPONSE;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.DETAIL_RESPONSE;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.BULK_EXPIRE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.EXPIRE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.EXPIRE_RESPONSE;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.LIST_RESPONSE;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.UPDATE_REQUEST;
import static com.dameokja.backend.ingredient.presentation.IngredientApiExamples.UPDATE_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.ingredient.presentation.request.IngredientBulkExpireRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientCreateRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientExpireRequest;
import com.dameokja.backend.ingredient.presentation.request.IngredientUpdateRequest;
import com.dameokja.backend.ingredient.presentation.response.IngredientCreateResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientExpireResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientListResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientResponse;
import com.dameokja.backend.ingredient.presentation.response.IngredientUpdateResponse;
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
            summary = "냉장고 재고 목록 조회",
            description = "커서 기반 무한 스크롤로 재고 목록을 조회합니다. 모든 정렬에서 만료 재고가 먼저 옵니다. "
                    + "다음 페이지는 같은 sort와 응답의 nextCursor를 보내고, 마지막 페이지면 nextCursor는 null입니다. "
                    + "상태(status)는 첫 페이지 요청 날짜(KST) 기준으로 계산합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "냉장고 재고 목록 조회 성공",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIST_RESPONSE))
            ),
            @ApiResponse(responseCode = "400", description = "size·sort·filter 형식 오류 (INGREDIENT-400-003), 유효하지 않은 커서 (INGREDIENT-400-004)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)"),
            @ApiResponse(responseCode = "404", description = "냉장고를 찾을 수 없음 (REFRIGERATOR-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<IngredientListResponse>> getList(
            @Parameter(hidden = true) Long userId,
            @Parameter(name = "refrigeratorId", in = ParameterIn.PATH, description = "조회할 냉장고 ID", required = true, example = "1")
            Long refrigeratorId,
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "이전 응답의 nextCursor. 첫 요청은 생략")
            String cursor,
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "페이지 크기 1~50, 기본 10", example = "10")
            String size,
            @Parameter(
                    name = "sort",
                    in = ParameterIn.QUERY,
                    description = "EXPIRATION_ASC(유통기한순, 기본) / CREATED_DESC(최근 등록순) / NAME_ASC(이름순, 한글 우선)",
                    schema = @Schema(allowableValues = {"EXPIRATION_ASC", "CREATED_DESC", "NAME_ASC"})
            )
            String sort,
            @Parameter(
                    name = "filter",
                    in = ParameterIn.QUERY,
                    description = "필터칩. 생략하면 전체. NORMAL(D+4 이후) / EXPIRING_SOON(D-0~D-3) / EXPIRED(유통기한 지남) "
                            + "/ REFRIGERATED(냉장) / FROZEN(냉동). 바꾸면 cursor 없이 첫 페이지부터 다시 조회",
                    schema = @Schema(allowableValues = {"NORMAL", "EXPIRING_SOON", "EXPIRED", "REFRIGERATED", "FROZEN"})
            )
            String filter);

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
                    + "측정 방식, 등록 방식, 등록일과 이미지는 변경할 수 없습니다. "
                    + "수정 결과가 같은 냉장고의 다른 재고와 이름·보관 방식·유통기한·측정 방식·단위가 같으면 "
                    + "그 재고의 수량을 수정한 재고에 더하고 기존 재고는 삭제합니다. 합친 내역은 mergedItems로 반환합니다."
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
            @ApiResponse(responseCode = "409", description = "합산 시 허용 수량 초과 (INGREDIENT-409-004)"),
            @ApiResponse(responseCode = "412", description = "ETag가 현재 버전과 다름 (INGREDIENT-412-001)"),
            @ApiResponse(responseCode = "422", description = "재고 수정 규칙 위반 (INGREDIENT-422-001~004)"),
            @ApiResponse(responseCode = "428", description = "If-Match 헤더가 누락됨 (INGREDIENT-428-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<IngredientUpdateResponse>> update(
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
            summary = "만료 재고 선택 만료 처리",
            description = "선택한 만료 재고를 한 번에 삭제합니다. "
                    + "선택한 재고 중 이 냉장고에 있고 유통기한이 오늘(Asia/Seoul)보다 이전인 재고만 삭제하며, "
                    + "이미 삭제됐거나 그사이 유통기한이 바뀐 재고는 건너뜁니다. 오늘 만료되는 재고(D-0)는 제외합니다. "
                    + "삭제한 재고 수만큼 이번 달 만료 처리 수를 늘립니다. 삭제 대상이 없어도 204를 반환하며, 되돌릴 수 없습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "만료 재고 선택 만료 처리 성공 (본문 없음)"),
            @ApiResponse(responseCode = "400", description = "ingredientIds 누락·빈 목록·null 포함·100개 초과 (INGREDIENT-400-003)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음 (REFRIGERATOR-403-001)"),
            @ApiResponse(responseCode = "404", description = "냉장고를 찾을 수 없음 (REFRIGERATOR-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<Void> expireSelected(
            @Parameter(hidden = true) Long userId,
            @Parameter(
                    name = "refrigeratorId",
                    in = ParameterIn.PATH,
                    description = "만료 재고를 처리할 냉장고 ID",
                    required = true,
                    example = "1"
            )
            Long refrigeratorId,
            @RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = IngredientBulkExpireRequest.class),
                            examples = @ExampleObject(name = "만료 재고 선택 만료 처리 요청", value = BULK_EXPIRE_REQUEST)
                    )
            )
            IngredientBulkExpireRequest request);

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
