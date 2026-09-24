package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

record NotificationCursor(LocalDateTime createdAt, Long notificationId, int shownCount) {
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

    String encode() {
        String value = createdAt + "|" + notificationId + "|" + shownCount;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
