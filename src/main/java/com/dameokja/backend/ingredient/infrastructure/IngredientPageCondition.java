package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 재고 목록 한 페이지의 조회 조건. 유통기한이 expirationFrom 이상이고 expirationBefore 미만인 재고만 조회하며,
 * null인 경계는 조건에서 뺀다. cursor가 null이면 그룹의 첫 페이지다.
 */
public record IngredientPageCondition(
        Long refrigeratorId,
        LocalDate expirationFrom,
        LocalDate expirationBefore,
        IngredientCursor cursor) {

    public static IngredientPageCondition of(Long refrigeratorId, IngredientExpiryGroup group,
                                             LocalDate baseDate, IngredientCursor cursor) {
        if (group == IngredientExpiryGroup.EXPIRED) {
            return new IngredientPageCondition(refrigeratorId, null, baseDate, cursor);
        }
        return new IngredientPageCondition(refrigeratorId, baseDate, null, cursor);
    }

    LocalDate cursorExpirationDate() {
        return cursor == null ? null : cursor.expirationDate();
    }

    LocalDateTime cursorCreatedAt() {
        return cursor == null ? null : cursor.createdAt();
    }

    String cursorName() {
        return cursor == null ? null : cursor.name();
    }

    Long cursorId() {
        return cursor == null ? null : cursor.ingredientId();
    }
}
