package com.dameokja.backend.notification.presentation;

import static com.dameokja.backend.notification.presentation.NotificationApiExamples.LIST_RESPONSE;
import static com.dameokja.backend.notification.presentation.NotificationApiExamples.POLLING_RESPONSE;
import static com.dameokja.backend.notification.presentation.NotificationApiExamples.UNREAD_COUNT_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.notification.presentation.request.NotificationListRequest;
import com.dameokja.backend.notification.presentation.response.NotificationListResponse;
import com.dameokja.backend.notification.presentation.response.NotificationPollingResponse;
import com.dameokja.backend.notification.presentation.response.UnreadNotificationCountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "알림", description = "알림 목록과 읽음 상태 API")
public interface NotificationApi {

    @Operation(summary = "알림 목록 조회", description = "본인이 받은 활성 냉장고 알림을 최신순으로 최대 99개 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "알림 목록 조회 성공",
                    content = @Content(examples = @ExampleObject(value = LIST_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "커서 형식 오류 (NOTI-400-001)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (COMMON-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (COMMON-500-001)")
    })
    ResponseEntity<SuccessResponse<NotificationListResponse>> getNotifications(
            @Parameter(hidden = true) Long userId,
            @Parameter(name = "refrigeratorId", in = ParameterIn.PATH,
                    description = "활성 냉장고 식별자", required = true) Long refrigeratorId,
            @Parameter(schema = @Schema(implementation = NotificationListRequest.class))
            NotificationListRequest request);

    @Operation(summary = "알림 개별 읽음 처리", description = "본인 수신 알림을 읽음 처리합니다. 이미 읽은 알림은 그대로 둡니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "읽음 처리 성공"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (COMMON-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 또는 알림 수신 권한이 없음"),
            @ApiResponse(responseCode = "404", description = "알림을 찾을 수 없음 (NOTI-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (COMMON-500-001)")
    })
    ResponseEntity<Void> readNotification(
            @Parameter(hidden = true) Long userId,
            @Parameter(name = "notificationId", in = ParameterIn.PATH,
                    description = "알림 식별자", required = true) Long notificationId);

    @Operation(summary = "알림 모두 읽음 처리", description = "활성 냉장고의 본인 미읽음 알림을 일괄 처리합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "읽음 처리 성공"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (COMMON-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (COMMON-500-001)")
    })
    ResponseEntity<Void> readAllNotifications(
            @Parameter(hidden = true) Long userId,
            @Parameter(name = "refrigeratorId", in = ParameterIn.PATH,
                    description = "활성 냉장고 식별자", required = true) Long refrigeratorId);

    @Operation(summary = "최신 알림 확인", description = "v1 polling용 API입니다. 새 ID를 확인하면 목록과 미읽음 수를 다시 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최신 알림 확인 성공",
                    content = @Content(examples = @ExampleObject(value = POLLING_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (COMMON-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (COMMON-500-001)")
    })
    ResponseEntity<SuccessResponse<NotificationPollingResponse>> pollLatestNotification(
            @Parameter(hidden = true) Long userId,
            @Parameter(name = "refrigeratorId", in = ParameterIn.PATH,
                    description = "활성 냉장고 식별자", required = true) Long refrigeratorId);

    @Operation(summary = "미읽음 알림 수 조회",
            description = "요청 시점의 본인·활성 냉장고 미읽음 수를 반환합니다. v1 polling에서 사용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "미읽음 수 조회 성공",
                    content = @Content(examples = @ExampleObject(value = UNREAD_COUNT_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (COMMON-401-001)"),
            @ApiResponse(responseCode = "403", description = "냉장고 접근 권한이 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (COMMON-500-001)")
    })
    ResponseEntity<SuccessResponse<UnreadNotificationCountResponse>> getUnreadCount(
            @Parameter(hidden = true) Long userId,
            @Parameter(name = "refrigeratorId", in = ParameterIn.PATH,
                    description = "활성 냉장고 식별자", required = true) Long refrigeratorId);
}
