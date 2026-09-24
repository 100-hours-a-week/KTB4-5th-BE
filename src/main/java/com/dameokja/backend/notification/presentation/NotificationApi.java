package com.dameokja.backend.notification.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.notification.presentation.request.NotificationListRequest;
import com.dameokja.backend.notification.presentation.response.NotificationListResponse;
import com.dameokja.backend.notification.presentation.response.NotificationPollingResponse;
import com.dameokja.backend.notification.presentation.response.UnreadNotificationCountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "알림", description = "알림 목록과 읽음 상태 API")
public interface NotificationApi {
    @Operation(summary = "알림 목록 조회")
    ResponseEntity<SuccessResponse<NotificationListResponse>> getNotifications(
            @Parameter(hidden = true) Long userId, Long refrigeratorId,
            NotificationListRequest request);

    @Operation(summary = "알림 개별 읽음 처리")
    ResponseEntity<Void> readNotification(
            @Parameter(hidden = true) Long userId, Long notificationId);

    @Operation(summary = "알림 모두 읽음 처리")
    ResponseEntity<Void> readAllNotifications(
            @Parameter(hidden = true) Long userId, Long refrigeratorId);

    @Operation(summary = "최신 알림 확인")
    ResponseEntity<SuccessResponse<NotificationPollingResponse>> pollLatestNotification(
            @Parameter(hidden = true) Long userId, Long refrigeratorId);

    @Operation(summary = "미읽음 알림 수 조회")
    ResponseEntity<SuccessResponse<UnreadNotificationCountResponse>> getUnreadCount(
            @Parameter(hidden = true) Long userId, Long refrigeratorId);
}
