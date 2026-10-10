package com.dameokja.backend.ingredient.application.list;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_CURSOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class IngredientListCursorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    private static final IngredientCursor POSITION =
            new IngredientCursor(TODAY, LocalDateTime.of(2026, 9, 20, 9, 0), "두부", 7L);

    private final IngredientListCursorCodec codec = new IngredientListCursorCodec(JsonMapper.builder().build());

    @Test
    void decodesTheCursorItEncoded() {
        IngredientListCursor cursor = cursor(IngredientSortType.NAME_ASC, 10L, TODAY);

        assertThat(codec.decode(codec.encode(cursor))).isEqualTo(cursor);
    }

    // 잘못된 Base64, JSON이 아닌 값
    @ParameterizedTest
    @ValueSource(strings = {"not-a-cursor@@", "aGVsbG8"})
    void rejectsTokenThatIsNotACursor(String token) {
        assertThatThrownBy(() -> codec.decode(token))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(INVALID_CURSOR));
    }

    @Test
    void matchesOnlySameSortAndRefrigeratorUpToToday() {
        IngredientListCursor cursor = cursor(IngredientSortType.NAME_ASC, 10L, TODAY);

        assertThat(cursor.matches(IngredientSortType.NAME_ASC, 10L, null, null, TODAY)).isTrue();
        assertThat(cursor.matches(IngredientSortType.CREATED_DESC, 10L, null, null, TODAY)).isFalse();
        assertThat(cursor.matches(IngredientSortType.NAME_ASC, 99L, null, null, TODAY)).isFalse();
        assertThat(cursor(IngredientSortType.NAME_ASC, 10L, TODAY.minusDays(1))
                .matches(IngredientSortType.NAME_ASC, 10L, null, null, TODAY)).isTrue();
        assertThat(cursor(IngredientSortType.NAME_ASC, 10L, TODAY.plusDays(1))
                .matches(IngredientSortType.NAME_ASC, 10L, null, null, TODAY)).isFalse();
    }

    @Test
    void doesNotMatchCursorWithMissingValues() {
        IngredientListCursor empty = codec.decode("e30");

        assertThat(empty.matches(IngredientSortType.NAME_ASC, 10L, null, null, TODAY)).isFalse();
    }

    @Test
    void doesNotMatchCursorIssuedForAnotherFilter() {
        IngredientListCursor cursor = IngredientListCursor.first(IngredientSortType.NAME_ASC, 10L, IngredientFilter.FROZEN, null, TODAY)
                .after(ingredient(TODAY));

        assertThat(cursor.matches(IngredientSortType.NAME_ASC, 10L, IngredientFilter.FROZEN, null, TODAY)).isTrue();
        assertThat(cursor.matches(IngredientSortType.NAME_ASC, 10L, IngredientFilter.REFRIGERATED, null, TODAY)).isFalse();
        assertThat(cursor.matches(IngredientSortType.NAME_ASC, 10L, null, null, TODAY)).isFalse();
    }

    @Test
    void preservesCategoryAndRequiresSameSelection() {
        IngredientListCursor cursor = IngredientListCursor.first(IngredientSortType.NAME_ASC, 10L, null,
                IngredientCategory.TOFU_BEAN, TODAY).after(ingredient(TODAY));
        IngredientListCursor decoded = codec.decode(codec.encode(cursor));

        assertThat(decoded).isEqualTo(cursor);
        assertThat(decoded.matches(IngredientSortType.NAME_ASC, 10L, null, IngredientCategory.TOFU_BEAN, TODAY)).isTrue();
        assertThat(decoded.matches(IngredientSortType.NAME_ASC, 10L, null, IngredientCategory.VEGETABLE, TODAY)).isFalse();
        assertThat(decoded.matches(IngredientSortType.NAME_ASC, 10L, null, null, TODAY)).isFalse();
    }

    @Test
    void acceptsLegacyCursorOnlyWithoutCategory() {
        String json = new String(Base64.getUrlDecoder().decode(codec.encode(cursor(IngredientSortType.NAME_ASC, 10L, TODAY))),
                StandardCharsets.UTF_8).replace(",\"category\":null", "");
        IngredientListCursor legacy = codec.decode(Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(json).doesNotContain("category");
        assertThat(legacy.matches(IngredientSortType.NAME_ASC, 10L, null, null, TODAY)).isTrue();
        assertThat(legacy.matches(IngredientSortType.NAME_ASC, 10L, null, IngredientCategory.TOFU_BEAN, TODAY)).isFalse();
    }

    private IngredientListCursor cursor(IngredientSortType sortType, Long refrigeratorId, LocalDate baseDate) {
        return new IngredientListCursor(sortType, refrigeratorId, null, null, baseDate, POSITION);
    }

    private Ingredient ingredient(LocalDate expirationDate) {
        IngredientDetails details = new IngredientDetails("두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), expirationDate);
        Ingredient ingredient = new Ingredient(new Refrigerator("냉장고", "2026-09"), details, RegistrationSource.DIRECT);
        ReflectionTestUtils.setField(ingredient, "id", 1L);
        ReflectionTestUtils.setField(ingredient, "createdAt", LocalDateTime.of(2026, 9, 20, 9, 0));
        return ingredient;
    }
}
