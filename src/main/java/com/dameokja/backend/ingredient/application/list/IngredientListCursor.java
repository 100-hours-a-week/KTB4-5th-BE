package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.StorageType;
import java.time.LocalDate;

/**
 * 클라이언트에 불투명 토큰으로 내려가는 목록 커서. baseDate는 첫 페이지 요청 날짜(KST)로 고정해
 * 스크롤 중 자정이 지나도 같은 기준으로 필터와 상태를 계산한다. position이 null이면 목록의 처음부터 읽는다.
 */
public record IngredientListCursor(
        IngredientSortType sortType,
        Long refrigeratorId,
        IngredientFilter filter,
        IngredientCategory category,
        LocalDate baseDate,
        IngredientCursor position) {

    static IngredientListCursor first(IngredientSortType sortType, Long refrigeratorId, IngredientFilter filter, IngredientCategory category,
                                      LocalDate baseDate) {
        return new IngredientListCursor(sortType, refrigeratorId, filter, category, baseDate, null);
    }

    IngredientListCursor after(Ingredient last) {
        return new IngredientListCursor(sortType, refrigeratorId, filter, category, baseDate, IngredientCursor.from(last));
    }

    StorageType storageType() {
        return filter == null ? null : filter.storageType();
    }

    boolean matches(IngredientSortType requestedSort, Long requestedRefrigeratorId, IngredientFilter requestedFilter,
                    IngredientCategory requestedCategory, LocalDate today) {
        return isComplete()
                && sortType == requestedSort
                && refrigeratorId.equals(requestedRefrigeratorId)
                && filter == requestedFilter
                && category == requestedCategory
                && !baseDate.isAfter(today);
    }

    private boolean isComplete() {
        return sortType != null && refrigeratorId != null && baseDate != null
                && position != null && position.expirationDate() != null && position.createdAt() != null
                && position.name() != null && position.ingredientId() != null;
    }
}
