package com.dameokja.backend.refrigerator.presentation;

import static com.dameokja.backend.refrigerator.presentation.InvitationApiExamples.EMPTY_RESPONSE;
import static com.dameokja.backend.refrigerator.presentation.InvitationApiExamples.FIXED_RESPONSE;
import static com.dameokja.backend.refrigerator.presentation.InvitationApiExamples.ISSUED_RESPONSE;
import static com.dameokja.backend.refrigerator.presentation.InvitationApiExamples.RETRIEVED_RESPONSE;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.refrigerator.presentation.response.InvitationDetailResponse;
import com.dameokja.backend.refrigerator.presentation.response.InvitationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "냉장고 초대")
public interface InvitationApi {
    @Operation(summary = "초대코드 발급", description = "OWNER만 발급. 일반 냉장고는 5분간 다회 사용하며 재발급하면 이전 코드는 무효화됩니다. "
            + "설정된 카테부 냉장고는 기존 최신 코드 하나를 만료 없이 유지하며 반복 발급에도 같은 코드를 반환합니다. CSRF 검증이 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "REFRIGERATOR-200-002",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, examples = @ExampleObject(value = ISSUED_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001 / COMMON-403-CSRF-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<SuccessResponse<InvitationResponse>> issueInvitationCode(@Parameter(hidden = true) Long userId,
            @Parameter(description = "냉장고 ID", example = "2") Long refrigeratorId);

    @Operation(summary = "유효한 초대코드 조회", description = "OWNER만 조회. 일반 코드는 실제 expiresAt을 반환하며 없거나 만료되면 data:null입니다. "
            + "카테부 고정 코드는 만료되지 않으며 기존 FE 호환을 위해 expiresAt은 2099-12-31T00:05:00+09:00을 반환합니다. 조회로 생성·연장하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "REFRIGERATOR-200-006. Cache-Control: no-store",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, examples = {
                    @ExampleObject(name = "유효한 코드", value = RETRIEVED_RESPONSE),
                    @ExampleObject(name = "카테부 고정 코드", value = FIXED_RESPONSE),
                    @ExampleObject(name = "코드 없음 또는 만료", value = EMPTY_RESPONSE)})),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<SuccessResponse<InvitationDetailResponse>> getValidInvitationCode(@Parameter(hidden = true) Long userId,
            @Parameter(description = "냉장고 ID", example = "2") Long refrigeratorId);
}
