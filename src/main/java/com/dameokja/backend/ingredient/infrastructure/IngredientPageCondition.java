package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 재고 목록 한 페이지의 조회 조건. expired가 true면 유통기한이 baseDate 이전인 만료 재고를,
 * false면 baseDate 이후(당일 포함)인 재고를 조회한다. cursor가 null이면 그룹의 첫 페이지다.
 */
public record IngredientPageCondition(
        Long refrigeratorId,
        boolean expired,
        LocalDate baseDate,
        IngredientCursor cursor) {

    public static IngredientPageCondition of(Long refrigeratorId, IngredientExpiryGroup group,
                                             LocalDate baseDate, IngredientCursor cursor) {
        boolean expired = group == IngredientExpiryGroup.EXPIRED;
        return new IngredientPageCondition(refrigeratorId, expired, baseDate, cursor);
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
