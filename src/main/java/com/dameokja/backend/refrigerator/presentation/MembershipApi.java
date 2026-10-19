package com.dameokja.backend.refrigerator.presentation;

import static com.dameokja.backend.refrigerator.presentation.MembershipApiExamples.JOIN_REQUEST;
import static com.dameokja.backend.refrigerator.presentation.MembershipApiExamples.JOINED_RESPONSE;
import static com.dameokja.backend.refrigerator.presentation.MembershipApiExamples.LEFT_RESPONSE;
import static com.dameokja.backend.refrigerator.presentation.MembershipApiExamples.MEMBERS_RESPONSE;
import static com.dameokja.backend.refrigerator.presentation.MembershipApiExamples.STATS_RESPONSE;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.refrigerator.presentation.request.JoinRefrigeratorRequest;
import com.dameokja.backend.refrigerator.presentation.response.JoinRefrigeratorResponse;
import com.dameokja.backend.refrigerator.presentation.response.LeaveRefrigeratorResponse;
import com.dameokja.backend.refrigerator.presentation.response.MemberCountResponse;
import com.dameokja.backend.refrigerator.presentation.response.MemberListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "냉장고 공유")
public interface MembershipApi {
    @Operation(summary = "공유 참여", description = "초대 발급 이력이 있어도 현재 다른 구성원이 없는 OWNER는 참여할 수 있습니다. "
            + "대상 소유자가 해당 냉장고를 현재 사용 중이어야 하며, 다른 냉장고를 사용 중이면 REFRIGERATOR-400-004로 거절합니다. "
            + "일반 코드는 발급 후 5분간 다회 사용하며 재발급하면 이전 코드는 무효입니다. "
            + "설정된 카테부 냉장고의 고정 코드만 만료 검사를 생략합니다. OWNER 포함 최대 150명이며 개인 재고를 보존합니다. CSRF 검증이 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "REFRIGERATOR-201-001",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, examples = @ExampleObject(value = JOINED_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "REFRIGERATOR-400-001 / REFRIGERATOR-400-002 / REFRIGERATOR-400-003 / REFRIGERATOR-400-004"),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001 / COMMON-403-CSRF-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-002 / REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "409", description = "REFRIGERATOR-409-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<SuccessResponse<JoinRefrigeratorResponse>> joinSharedRefrigerator(@Parameter(hidden = true) Long userId,
            @RequestBody(required = true, description = "6자리 영문 대문자·숫자 초대코드",
                    content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = JoinRefrigeratorRequest.class),
                            examples = @ExampleObject(value = JOIN_REQUEST)))
            JoinRefrigeratorRequest request);

    @Operation(summary = "냉장고 참여자 목록 조회", description = "인증된 요청자의 currentUserId(String), 유효 구성원 목록과 인원·최대 150명을 반환합니다. 기존 ID 비교로 본인·방장 여부를 판단할 수 있습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "REFRIGERATOR-200-003",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, examples = @ExampleObject(value = MEMBERS_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<SuccessResponse<MemberListResponse>> getRefrigeratorMembers(@Parameter(hidden = true) Long userId,
            @Parameter(description = "냉장고 ID", example = "2") Long refrigeratorId);

    @Operation(summary = "냉장고 참여자 인원수 조회")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "REFRIGERATOR-200-004",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, examples = @ExampleObject(value = STATS_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<SuccessResponse<MemberCountResponse>> getRefrigeratorMemberCount(@Parameter(hidden = true) Long userId,
            @Parameter(description = "냉장고 ID", example = "2") Long refrigeratorId);

    @Operation(summary = "공유 나가기", description = "MEMBER만 나갈 수 있으며 개인 공간으로 복귀하고 재고를 보존합니다. CSRF 검증이 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "REFRIGERATOR-200-005",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, examples = @ExampleObject(value = LEFT_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "REFRIGERATOR-400-005"),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-001 / USER-403-001 / COMMON-403-CSRF-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-001 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<SuccessResponse<LeaveRefrigeratorResponse>> leaveSharedRefrigerator(@Parameter(hidden = true) Long userId,
            @Parameter(description = "냉장고 ID", example = "2") Long refrigeratorId);

    @Operation(summary = "공유 내보내기", description = "OWNER만 같은 냉장고의 다른 MEMBER를 내보냅니다. 대상은 목록의 userId이며 성공 본문은 없습니다. CSRF 검증이 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "본문 없음"),
            @ApiResponse(responseCode = "401", description = "GLOBAL-401-001 / ACCESS_TOKEN_EXPIRED / ACCESS_TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "REFRIGERATOR-403-002 / REFRIGERATOR-403-001 / USER-403-001 / COMMON-403-CSRF-001"),
            @ApiResponse(responseCode = "404", description = "REFRIGERATOR-404-003 / USER-404-001"),
            @ApiResponse(responseCode = "410", description = "REFRIGERATOR-410-001"),
            @ApiResponse(responseCode = "500", description = "GLOBAL-500-001")})
    ResponseEntity<Void> removeRefrigeratorMember(@Parameter(hidden = true) Long userId,
            @Parameter(description = "냉장고 ID", example = "2") Long refrigeratorId,
            @Parameter(description = "참여자 목록의 userId", example = "3") Long targetUserId);
}
