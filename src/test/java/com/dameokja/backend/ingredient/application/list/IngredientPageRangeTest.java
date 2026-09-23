package com.dameokja.backend.ingredient.application.list;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IngredientPageRangeTest {
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 23);

    // from·to는 기준일로부터 며칠 뒤인지. 비어 있으면 경계 없음(null)
    @ParameterizedTest
    @CsvSource({
            "EXPIRED,     ,              , -1",
            "EXPIRED,     EXPIRED,       , -1",
            "EXPIRED,     REFRIGERATED,  , -1",
            "NOT_EXPIRED, ,             0, ",
            "NOT_EXPIRED, EXPIRING_SOON,0, 3",
            "NOT_EXPIRED, NORMAL,       4, ",
            "NOT_EXPIRED, FROZEN,       0, "
    })
    void convertsGroupAndFilterIntoExpirationRange(IngredientExpiryGroup group, IngredientFilter filter,
                                                  Integer fromDays, Integer toDays) {
        IngredientPageRange range = IngredientPageRange.of(group, filter, BASE_DATE);

        assertThat(range.from()).isEqualTo(fromDays == null ? null : BASE_DATE.plusDays(fromDays));
        assertThat(range.to()).isEqualTo(toDays == null ? null : BASE_DATE.plusDays(toDays));
    }
}
