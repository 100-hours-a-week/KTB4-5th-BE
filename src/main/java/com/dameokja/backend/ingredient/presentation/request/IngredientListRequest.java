package com.dameokja.backend.ingredient.presentation.request;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_INPUT;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.IngredientSortType;

/**
 * 재고 목록 조회 쿼리 파라미터. 타입 변환 실패도 재고 입력 오류(INGREDIENT-400-003)로 응답하도록 문자열로 받아 직접 변환한다.
 */
public record IngredientListRequest(String cursor, String size, String sort) {
    private static final int DEFAULT_SIZE = 10;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 50;
    private static final IngredientSortType DEFAULT_SORT = IngredientSortType.EXPIRATION_ASC;

    public String cursorToken() {
        return cursor == null || cursor.isBlank() ? null : cursor;
    }

    public int pageSize() {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        int pageSize = parseSize();
        if (pageSize < MIN_SIZE || pageSize > MAX_SIZE) {
            throw new CustomException(INVALID_INPUT);
        }
        return pageSize;
    }

    public IngredientSortType sortType() {
        if (sort == null) {
            return DEFAULT_SORT;
        }
        try {
            return IngredientSortType.valueOf(sort);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(INVALID_INPUT);
        }
    }

    private int parseSize() {
        try {
            return Integer.parseInt(size);
        } catch (NumberFormatException exception) {
            throw new CustomException(INVALID_INPUT);
        }
    }
}
