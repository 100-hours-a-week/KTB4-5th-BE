package com.dameokja.backend.notification.domain;

public record NotificationChanged(Long userId, Long refrigeratorId) {
    public NotificationChanged {
        requirePositiveId(userId);
        requirePositiveId(refrigeratorId);
    }

    private static void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("알림 갱신 대상 식별자는 양수여야 합니다.");
        }
    }
}
