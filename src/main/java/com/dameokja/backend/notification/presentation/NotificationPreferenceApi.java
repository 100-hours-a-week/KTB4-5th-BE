package com.dameokja.backend.notification.presentation;

import static com.dameokja.backend.notification.presentation.NotificationPreferenceApiExamples.CHANGE_REQUEST;
import static com.dameokja.backend.notification.presentation.NotificationPreferenceApiExamples.CHANGE_RESPONSE;
import static com.dameokja.backend.notification.presentation.NotificationPreferenceApiExamples.PREFERENCES_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.notification.presentation.request.NotificationPreferencesRequest;
import com.dameokja.backend.notification.presentation.response.NotificationPreferencesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "알림 설정", description = "알림 유형별 수신 설정 API")
public interface NotificationPreferenceApi {

    @Operation(summary = "알림 설정 변경", description = "전달한 유형만 수정하고 생략한 유형은 유지합니다. 같은 값으로 다시 요청해도 상태는 같습니다. "
            + "저장된 전체 설정을 반환하며 푸시 구독 상태는 변경하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "알림 설정 변경 성공 (NOTI-200-002)",
                    content = @Content(examples = @ExampleObject(value = CHANGE_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "목록 누락·유형 중복·지원하지 않는 유형·isEnabled 누락 (GLOBAL-400-001)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "CSRF 토큰 오류 (COMMON-403-CSRF-001)"),
            @ApiResponse(responseCode = "404", description = "저장된 설정 없음 (NOTI-404-002)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<NotificationPreferencesResponse>> changePreferences(@Parameter(hidden = true) Long userId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = NotificationPreferencesRequest.class),
                    examples = @ExampleObject(value = CHANGE_REQUEST))) NotificationPreferencesRequest request);

    @Operation(summary = "알림 설정 조회",
            description = "본인의 알림 유형별 수신 여부를 반환합니다. EXPIRATION은 임박·만료 알림, RECIPE는 추천 알림, ANALYSIS는 분석 완료 푸시입니다. 각 설정은 독립적으로 적용됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "알림 설정 조회 성공",
                    content = @Content(examples = @ExampleObject(value = PREFERENCES_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<NotificationPreferencesResponse>> getPreferences(
            @Parameter(hidden = true) Long userId);
}
