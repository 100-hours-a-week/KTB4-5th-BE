package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.ingredient.application.ExpiredIngredientCountService;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import com.dameokja.backend.notification.domain.NotificationRecipient;
import com.dameokja.backend.notification.infrastructure.NotificationRecipientRepository;
import com.dameokja.backend.notification.infrastructure.NotificationRepository;
import com.dameokja.backend.notification.presentation.request.NotificationListRequest;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {
    private static final int PAGE_SIZE = 10;
    private static final int DISPLAY_LIMIT = 99;

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final ExpiredIngredientCountService expiredIngredientCountService;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final Clock clock;

    public NotificationListResult getNotifications(Long userId, Long refrigeratorId,
            NotificationListRequest request) {
        validateRefrigeratorAccess(userId, refrigeratorId);
        NotificationCursor cursor = NotificationCursor.parse(request.cursor());
        validateCursor(cursor);
        int size = Math.min(PAGE_SIZE, DISPLAY_LIMIT - cursor.shownCount());
        if (size <= 0) {
            return new NotificationListResult(List.of(), null,
                    expiredIngredientCountService.count(refrigeratorId));
        }
        List<NotificationRecipient> fetched = recipientRepository.findPage(userId, refrigeratorId,
                request.typeOrDefault(), cursor.createdAt(), cursor.notificationId(),
                PageRequest.of(0, size + 1));
        boolean hasMore = fetched.size() > size;
        List<NotificationRecipient> page = hasMore ? fetched.subList(0, size) : fetched;
        String nextCursor = nextCursor(page, cursor, hasMore);
        long expiredIngredientsNum = expiredIngredientCountService.count(refrigeratorId);
        return new NotificationListResult(page, nextCursor, expiredIngredientsNum);
    }

    public String getLatestNotificationId(Long userId, Long refrigeratorId) {
        validateRefrigeratorAccess(userId, refrigeratorId);
        List<NotificationRecipient> latest = recipientRepository.findPage(userId, refrigeratorId,
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
        validateRefrigeratorAccess(userId, notification.getRefrigerator().getId());
        NotificationRecipient recipient = recipientRepository
                .findByNotificationIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new CustomException(GlobalExceptionCode.FORBIDDEN));
        recipient.markRead(LocalDateTime.now(clock));
    }

    @Transactional
    public void readAllNotifications(Long userId, Long refrigeratorId) {
        validateRefrigeratorAccess(userId, refrigeratorId);
        recipientRepository.markAllRead(userId, refrigeratorId, LocalDateTime.now(clock));
    }

    private void validateRefrigeratorAccess(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
    }

    private void validateCursor(NotificationCursor cursor) {
        if (cursor.shownCount() < 0 || cursor.shownCount() >= DISPLAY_LIMIT
                || ((cursor.createdAt() == null) != (cursor.notificationId() == null))) {
            throw new CustomException(NotificationExceptionCode.INVALID_CURSOR);
        }
        if (cursor.shownCount() == 0 && cursor.createdAt() != null
                || cursor.shownCount() > 0 && cursor.createdAt() == null) {
            throw new CustomException(NotificationExceptionCode.INVALID_CURSOR);
        }
    }

    private String nextCursor(List<NotificationRecipient> page, NotificationCursor cursor,
            boolean hasMore) {
        int shownCount = cursor.shownCount() + page.size();
        if (!hasMore || shownCount >= DISPLAY_LIMIT || page.isEmpty()) {
            return null;
        }
        Notification notification = page.getLast().getNotification();
        return new NotificationCursor(notification.getCreatedAt(), notification.getId(), shownCount)
                .encode();
    }
}
