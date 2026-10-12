package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.application.ExpiredIngredientCountService;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import com.dameokja.backend.notification.domain.NotificationRecipient;
import com.dameokja.backend.notification.infrastructure.NotificationRecipientRepository;
import com.dameokja.backend.notification.infrastructure.NotificationRepository;
import com.dameokja.backend.notification.presentation.request.NotificationListRequest;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final ExpiredIngredientCountService expiredIngredientCountService;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final Clock clock;

    public NotificationListResult getNotifications(Long userId, Long refrigeratorId,
            NotificationListRequest request) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        NotificationCursor cursor = NotificationCursor.parse(request.cursor());
        int size = cursor.pageSize();

        List<NotificationRecipient> recipients = recipientRepository.findPageByCursor(
                userId, refrigeratorId,
                request.typeOrDefault(), cursor.createdAt(), cursor.notificationId(),
                PageRequest.of(0, size + 1));

        List<NotificationRecipient> page = cursor.selectPage(recipients);
        String nextCursor = cursor.createNextCursorToken(recipients);
        long expiredIngredientsNum = expiredIngredientCountService.count(refrigeratorId);
        return new NotificationListResult(page, nextCursor, expiredIngredientsNum);
    }

    public String getLatestNotificationId(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        List<NotificationRecipient> latest = recipientRepository.findPageByCursor(
                userId, refrigeratorId,
                "ALL", null, null, PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            return null;
        }
        return latest.getFirst().getNotification().getId().toString();
    }

    @Transactional
    public void readNotification(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new CustomException(NotificationExceptionCode.NOT_FOUND));
        refrigeratorAccessService.validateReadAccess(userId, notification.getRefrigerator().getId());
        NotificationRecipient recipient = recipientRepository
                .findByNotificationIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new CustomException(GlobalExceptionCode.FORBIDDEN));
        recipient.markRead(BusinessTime.now(clock));
    }

    @Transactional
    public void readAllNotifications(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        recipientRepository.markAllRead(userId, refrigeratorId, BusinessTime.now(clock));
    }

    @Transactional
    public int deleteUnreferencedNotifications() {
        return notificationRepository.deleteUnreferencedNotifications();
    }
}
