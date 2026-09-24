package com.dameokja.backend.notification.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.notification.application.NotificationService;
import com.dameokja.backend.notification.application.UnreadNotificationCountService;
import com.dameokja.backend.notification.presentation.request.NotificationListRequest;
import com.dameokja.backend.notification.presentation.response.NotificationListResponse;
import com.dameokja.backend.notification.presentation.response.NotificationPollingResponse;
import com.dameokja.backend.notification.presentation.response.UnreadNotificationCountResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class NotificationController implements NotificationApi {
    private static final String LIST_CODE = "NOTI-200-001";
    private static final String LIST_MESSAGE = "알림 목록 조회 성공";
    private static final String UNREAD_COUNT_MESSAGE = "미읽음 알림 개수 조회 성공";
    private static final String POLLING_MESSAGE = "최신 알림 확인 성공";
    private final NotificationService notificationService;
    private final UnreadNotificationCountService unreadNotificationCountService;

    @Override
    @GetMapping("/refrigerators/{refrigeratorId}/notifications")
    public ResponseEntity<SuccessResponse<NotificationListResponse>> getNotifications(
            @CurrentUserId Long userId,
            @PathVariable Long refrigeratorId,
            @Valid @ModelAttribute NotificationListRequest request) {
        NotificationListResponse response = NotificationListResponse.from(
                notificationService.getNotifications(userId, refrigeratorId, request));
        return ResponseEntity.ok(SuccessResponse.of(LIST_CODE, LIST_MESSAGE, response));
    }

    @Override
    @PatchMapping("/notifications/{notificationId}/read")
    public ResponseEntity<Void> readNotification(
            @CurrentUserId Long userId,
            @PathVariable Long notificationId) {
        notificationService.readNotification(userId, notificationId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PatchMapping("/refrigerators/{refrigeratorId}/notifications/read-all")
    public ResponseEntity<Void> readAllNotifications(
            @CurrentUserId Long userId,
            @PathVariable Long refrigeratorId) {
        notificationService.readAllNotifications(userId, refrigeratorId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @GetMapping("/refrigerators/{refrigeratorId}/notifications/stream")
    public ResponseEntity<SuccessResponse<NotificationPollingResponse>> pollLatestNotification(
            @CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        String notificationId = notificationService.getLatestNotificationId(userId, refrigeratorId);
        NotificationPollingResponse response = new NotificationPollingResponse(notificationId);
        SuccessResponse<NotificationPollingResponse> body = SuccessResponse.of(
                LIST_CODE, POLLING_MESSAGE, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(body);
    }

    @Override
    @GetMapping("/refrigerators/{refrigeratorId}/notifications/unread-count")
    public ResponseEntity<SuccessResponse<UnreadNotificationCountResponse>> getUnreadCount(
            @CurrentUserId Long userId,
            @PathVariable Long refrigeratorId) {
        long unreadCount = unreadNotificationCountService.getUnreadCount(userId, refrigeratorId);
        UnreadNotificationCountResponse response = new UnreadNotificationCountResponse(
                refrigeratorId.toString(), unreadCount);
        SuccessResponse<UnreadNotificationCountResponse> body = SuccessResponse.of(
                LIST_CODE, UNREAD_COUNT_MESSAGE, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(body);
    }
}
