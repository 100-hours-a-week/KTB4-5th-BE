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

    // 필터가 두 유통기한 그룹을 모두 포함하면 범위 조건이 없고, 한 그룹만 포함하면 그 그룹의 범위다.
    static IngredientPageRange of(IngredientFilter filter, LocalDate baseDate) {
        boolean includesExpired = filter == null || filter.includes(IngredientExpiryGroup.EXPIRED);
        boolean includesNotExpired = filter == null || filter.includes(IngredientExpiryGroup.NOT_EXPIRED);
        if (includesExpired && includesNotExpired) {
            return new IngredientPageRange(null, null);
        }
        IngredientExpiryGroup group = includesExpired ? IngredientExpiryGroup.EXPIRED : IngredientExpiryGroup.NOT_EXPIRED;
        return of(group, filter, baseDate);
    }
}
