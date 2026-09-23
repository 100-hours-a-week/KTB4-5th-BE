package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.StorageType;
import java.time.LocalDate;

/**
 * 클라이언트에 불투명 토큰으로 내려가는 목록 커서. baseDate는 첫 페이지 요청 날짜(KST)로 고정해
 * 스크롤 중 자정이 지나도 같은 기준으로 만료 그룹과 상태를 계산한다. position이 null이면 그룹의 처음부터 읽는다.
 */
public record IngredientListCursor(
        IngredientSortType sortType,
        Long refrigeratorId,
        IngredientFilter filter,
        LocalDate baseDate,
        IngredientExpiryGroup group,
        IngredientCursor position) {

    // 필터가 만료 그룹을 제외하면(임박·정상) 비만료 그룹부터 읽는다.
    static IngredientListCursor first(IngredientSortType sortType, Long refrigeratorId, IngredientFilter filter,
                                      LocalDate baseDate) {
        IngredientExpiryGroup firstGroup = includes(filter, IngredientExpiryGroup.EXPIRED)
                ? IngredientExpiryGroup.EXPIRED
                : IngredientExpiryGroup.NOT_EXPIRED;
        return new IngredientListCursor(sortType, refrigeratorId, filter, baseDate, firstGroup, null);
    }

    IngredientListCursor after(Ingredient last) {
        IngredientExpiryGroup lastGroup = last.getExpirationDate().isBefore(baseDate)
                ? IngredientExpiryGroup.EXPIRED
                : IngredientExpiryGroup.NOT_EXPIRED;
        return new IngredientListCursor(sortType, refrigeratorId, filter, baseDate, lastGroup, IngredientCursor.from(last));
    }

    boolean includes(IngredientExpiryGroup targetGroup) {
        return includes(filter, targetGroup);
    }

    StorageType storageType() {
        return filter == null ? null : filter.storageType();
    }

    boolean matches(IngredientSortType requestedSort, Long requestedRefrigeratorId, IngredientFilter requestedFilter,
                    LocalDate today) {
        return isComplete()
                && sortType == requestedSort
                && refrigeratorId.equals(requestedRefrigeratorId)
                && filter == requestedFilter
                && !baseDate.isAfter(today);
    }

    private static boolean includes(IngredientFilter filter, IngredientExpiryGroup targetGroup) {
        return filter == null || filter.includes(targetGroup);
    }

    private boolean isComplete() {
        return sortType != null && refrigeratorId != null && baseDate != null && group != null
                && position != null && position.expirationDate() != null && position.createdAt() != null
                && position.name() != null && position.ingredientId() != null;
    }
}
