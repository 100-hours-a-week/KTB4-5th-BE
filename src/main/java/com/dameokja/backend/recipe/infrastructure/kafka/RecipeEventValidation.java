package com.dameokja.backend.recipe.infrastructure.kafka;

import java.time.Instant;
import java.util.Objects;

final class RecipeEventValidation {
    private RecipeEventValidation() {}

    static void validate(Instant occurredAt, String requestId, Long userId, Long refrigeratorId) {
        Objects.requireNonNull(occurredAt);
        if (requestId == null || requestId.isBlank() || userId == null || userId <= 0 || refrigeratorId == null || refrigeratorId <= 0) {
            throw new IllegalArgumentException("추천 이벤트의 요청·사용자·냉장고가 올바르지 않습니다.");
        }
    }

    static void validateKey(Long userId, String key) {
        if (!userId.toString().equals(key)) { throw new IllegalArgumentException("Kafka key는 payload.userId와 같아야 합니다."); }
    }
}
