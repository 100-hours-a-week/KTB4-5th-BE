package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import com.dameokja.backend.notification.domain.NotificationRecipient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

record NotificationCursor(LocalDateTime createdAt, Long notificationId, int shownCount) {
    private static final int PAGE_SIZE = 10;
    private static final int DISPLAY_LIMIT = 99;

    NotificationCursor {
        boolean firstPage = shownCount == 0 && createdAt == null && notificationId == null;
        boolean nextPage = shownCount > 0 && shownCount < DISPLAY_LIMIT
                && createdAt != null && notificationId != null;
        if (!firstPage && !nextPage) {
            throw new CustomException(NotificationExceptionCode.INVALID_CURSOR);
        }
    }

    static NotificationCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return new NotificationCursor(null, null, 0);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            return new NotificationCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (RuntimeException exception) {
            throw new CustomException(NotificationExceptionCode.INVALID_CURSOR);
        }
    }

    int pageSize() {
        return Math.min(PAGE_SIZE, DISPLAY_LIMIT - shownCount);
    }

    List<NotificationRecipient> selectPage(List<NotificationRecipient> recipients) {
        int size = pageSize();
        return recipients.size() > size ? recipients.subList(0, size) : recipients;
    }

    String createNextCursorToken(List<NotificationRecipient> recipients) {
        int size = pageSize();
        int nextShownCount = shownCount + size;
        if (recipients.size() <= size || nextShownCount >= DISPLAY_LIMIT) {
            return null;
        }
        Notification notification = recipients.get(size - 1).getNotification();
        return new NotificationCursor(notification.getCreatedAt(), notification.getId(), nextShownCount)
                .encode();
    }

    String encode() {
        String value = createdAt + "|" + notificationId + "|" + shownCount;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
