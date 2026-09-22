package com.dameokja.backend.notification.presentation;

import com.dameokja.backend.notification.presentation.request.NotificationListRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "알림", description = "알림 목록과 읽음 상태 API")
public interface NotificationApi {

    @Operation(summary = "알림 목록 조회")
    ResponseEntity<Void> getNotifications(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "활성 냉장고 식별자") Long refrigeratorId,
            NotificationListRequest request);

    @Operation(summary = "알림 개별 읽음 처리")
    ResponseEntity<Void> readNotification(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "알림 식별자") Long notificationId);

    @Operation(summary = "알림 모두 읽음 처리")
    ResponseEntity<Void> readAllNotifications(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "활성 냉장고 식별자") Long refrigeratorId);

    @Operation(summary = "미읽음 알림 수 조회")
    ResponseEntity<Void> getUnreadCount(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "활성 냉장고 식별자") Long refrigeratorId);
}
