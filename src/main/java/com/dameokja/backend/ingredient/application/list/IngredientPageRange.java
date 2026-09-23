package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import java.time.LocalDate;

/**
 * 한 유통기한 그룹을 조회할 때의 유통기한 범위(양 끝 포함). null인 경계는 조건이 없다는 뜻이다.
 */
record IngredientPageRange(LocalDate from, LocalDate to) {

    static IngredientPageRange of(IngredientExpiryGroup group, IngredientFilter filter, LocalDate baseDate) {
        if (group == IngredientExpiryGroup.EXPIRED) {
            return new IngredientPageRange(null, baseDate.minusDays(1));
        }
        if (filter == null) {
            return new IngredientPageRange(baseDate, null);
        }
        return new IngredientPageRange(filter.notExpiredFrom(baseDate), filter.notExpiredTo(baseDate));
    }
}
