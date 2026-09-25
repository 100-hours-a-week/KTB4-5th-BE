package com.dameokja.backend.notification.presentation;

import static com.dameokja.backend.notification.presentation.NotificationPreferenceApiExamples.PREFERENCES_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.notification.presentation.response.NotificationPreferencesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "알림 설정", description = "알림 유형별 수신 설정 API")
public interface NotificationPreferenceApi {

    @Operation(summary = "알림 설정 조회",
            description = "본인의 알림 유형별 수신 여부를 반환합니다. EXPIRATION은 임박·만료 알림, RECIPE는 추천 알림입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "알림 설정 조회 성공",
                    content = @Content(examples = @ExampleObject(value = PREFERENCES_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (COMMON-401-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (COMMON-500-001)")
    })
    ResponseEntity<SuccessResponse<NotificationPreferencesResponse>> getPreferences(
            @Parameter(hidden = true) Long userId);
}
