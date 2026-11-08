package com.dameokja.backend.notification.domain;

import java.time.LocalDate;
import java.util.Objects;

public record ExpirationCheck(LocalDate businessDate, Long refrigeratorId) {
    public ExpirationCheck {
        Objects.requireNonNull(businessDate);
        if (refrigeratorId == null || refrigeratorId <= 0) {
            throw new IllegalArgumentException("냉장고 ID는 양수여야 합니다.");
        }
    }

    public String kafkaKey() {
        return refrigeratorId.toString();
    }
}
