package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

record NotificationCursor(LocalDateTime createdAt, Long notificationId, int shownCount) {
    private static final int SEGMENT_COUNT = 3;

    static NotificationCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return new NotificationCursor(null, null, 0);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != SEGMENT_COUNT) {
                throw new IllegalArgumentException("알림 커서는 3개 항목이어야 합니다.");
            }
            long notificationId = Long.parseLong(parts[1]);
            if (notificationId <= 0) {
                throw new IllegalArgumentException("알림 ID는 양수여야 합니다.");
            }
            return new NotificationCursor(LocalDateTime.parse(parts[0]), notificationId,
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
