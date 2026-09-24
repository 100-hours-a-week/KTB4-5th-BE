package com.dameokja.backend.notification.presentation;

import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.notification.application.NotificationService;
import com.dameokja.backend.notification.application.UnreadNotificationCountService;
import com.dameokja.backend.notification.presentation.request.NotificationListRequest;
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
    private final NotificationService notificationService;
    private final UnreadNotificationCountService unreadNotificationCountService;

    @Override
    @GetMapping("/refrigerators/{refrigeratorId}/notifications")
    public ResponseEntity<Void> getNotifications(
            @CurrentUserId Long userId,
            @PathVariable Long refrigeratorId,
            @Valid @ModelAttribute NotificationListRequest request) {
        notificationService.getNotifications(userId, refrigeratorId, request);
        return ResponseEntity.ok().build();
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
    @GetMapping("/refrigerators/{refrigeratorId}/notifications/unread-count")
    public ResponseEntity<Void> getUnreadCount(
            @CurrentUserId Long userId,
            @PathVariable Long refrigeratorId) {
        unreadNotificationCountService.getUnreadCount(userId, refrigeratorId);
        // 실제 구현 시 조회한 개수와 냉장고 식별자를 응답 DTO로 구성한다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
