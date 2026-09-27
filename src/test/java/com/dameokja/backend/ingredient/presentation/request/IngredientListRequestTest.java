package com.dameokja.backend.ingredient.presentation.request;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_INPUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngredientListRequestTest {

    @Test
    void usesDefaultsWhenParametersAreOmitted() {
        IngredientListRequest request = new IngredientListRequest(null, null, null, null);

        assertThat(request.cursorToken()).isNull();
        assertThat(request.pageSize()).isEqualTo(10);
        assertThat(request.sortType()).isEqualTo(IngredientSortType.EXPIRATION_ASC);
        assertThat(request.ingredientFilter()).isNull();
    }

    @Test
    void readsProvidedParameters() {
        IngredientListRequest request = new IngredientListRequest("token", "50", "NAME_ASC", "EXPIRING_SOON");

        assertThat(request.cursorToken()).isEqualTo("token");
        assertThat(request.pageSize()).isEqualTo(50);
        assertThat(request.sortType()).isEqualTo(IngredientSortType.NAME_ASC);
        assertThat(request.ingredientFilter()).isEqualTo(IngredientFilter.EXPIRING_SOON);
    }

    @Test
    void treatsBlankCursorAsFirstPage() {
        assertThat(new IngredientListRequest(" ", null, null, null).cursorToken()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "ten", ""})
    void rejectsSizeOutOfRange(String size) {
        assertInvalidInput(() -> new IngredientListRequest(null, size, null, null).pageSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expiration_asc", "PRICE_ASC", ""})
    void rejectsUnknownSort(String sort) {
        assertInvalidInput(() -> new IngredientListRequest(null, null, sort, null).sortType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "VEGETABLE", ""})
    void rejectsUnknownFilter(String filter) {
        assertInvalidInput(() -> new IngredientListRequest(null, null, null, filter).ingredientFilter());
    }

    private void assertInvalidInput(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(INVALID_INPUT));
    }
}
