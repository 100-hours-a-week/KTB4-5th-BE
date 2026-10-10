package com.dameokja.backend.ingredient.application.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
class IngredientPageReaderTest {
    private static final Long REFRIGERATOR_ID = 10L;
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 23);

    @Mock private IngredientRepository ingredientRepository;
    private IngredientPageReader ingredientPageReader;

    @BeforeEach
    void setUp() {
        ingredientPageReader = new IngredientPageReader(ingredientRepository);
    }

    @Test
    void readsWholeExpirationOrderedListInOneQuery() {
        Ingredient expired = mock(Ingredient.class);
        Ingredient notExpired = mock(Ingredient.class);
        when(ingredientRepository.findExpirationAscPage(REFRIGERATOR_ID, null, null, null, null, null, null, null, null, null, Limit.of(3)))
                .thenReturn(List.of(expired, notExpired));

        List<Ingredient> rows = ingredientPageReader.read(firstCursor(IngredientSortType.EXPIRATION_ASC), 3);

        assertThat(rows).containsExactly(expired, notExpired);
        verifyNoMoreInteractions(ingredientRepository);
    }

    @Test
    void readsWholeCreatedOrderedListInOneQuery() {
        List<Ingredient> expired = List.of(mock(Ingredient.class), mock(Ingredient.class));
        when(ingredientRepository.findCreatedDescPage(REFRIGERATOR_ID, null, null, null, null, null, null, null, null, null, Limit.of(2)))
                .thenReturn(expired);

        List<Ingredient> rows = ingredientPageReader.read(firstCursor(IngredientSortType.CREATED_DESC), 2);

        assertThat(rows).isEqualTo(expired);
        verifyNoMoreInteractions(ingredientRepository);
    }

    @Test
    void continuesWholeNameOrderedListAfterCursorPosition() {
        IngredientCursor position = new IngredientCursor(BASE_DATE, LocalDateTime.of(2026, 9, 20, 9, 0), "두부", 7L);
        IngredientListCursor cursor = new IngredientListCursor(IngredientSortType.NAME_ASC, REFRIGERATOR_ID, null, null, null, BASE_DATE,
                position);
        List<Ingredient> notExpired = List.of(mock(Ingredient.class));
        when(ingredientRepository.findNameAscPage(REFRIGERATOR_ID, null, null, null, null, null, BASE_DATE,
                position.createdAt(), "두부", 7L, Limit.of(5))).thenReturn(notExpired);

        List<Ingredient> rows = ingredientPageReader.read(cursor, 5);

        assertThat(rows).isEqualTo(notExpired);
        verifyNoMoreInteractions(ingredientRepository);
    }

    @ParameterizedTest
    @CsvSource({"EXPIRED, , -1", "EXPIRING_SOON, 0, 3", "NORMAL, 4, "})
    void passesStatusFilterExpirationBounds(IngredientFilter filter, Integer fromDays, Integer toDays) {
        List<Ingredient> ingredients = List.of(mock(Ingredient.class));
        IngredientListCursor cursor = IngredientListCursor.first(
                IngredientSortType.EXPIRATION_ASC, REFRIGERATOR_ID, filter, IngredientCategory.TOFU_BEAN, "두부", BASE_DATE);
        LocalDate from = fromDays == null ? null : BASE_DATE.plusDays(fromDays);
        LocalDate to = toDays == null ? null : BASE_DATE.plusDays(toDays);
        when(ingredientRepository.findExpirationAscPage(REFRIGERATOR_ID, from, to, null, IngredientCategory.TOFU_BEAN, "%두부%", null, null, null, null, Limit.of(3)))
                .thenReturn(ingredients);

        List<Ingredient> rows = ingredientPageReader.read(cursor, 3);

        assertThat(rows).isEqualTo(ingredients);
        verifyNoMoreInteractions(ingredientRepository);
    }

    @ParameterizedTest
    @EnumSource(value = StorageType.class, names = {"REFRIGERATED", "FROZEN"})
    void passesStorageTypeWithoutSplittingExpiryGroups(StorageType storageType) {
        Ingredient expired = mock(Ingredient.class);
        Ingredient notExpired = mock(Ingredient.class);
        IngredientListCursor cursor = IngredientListCursor.first(
                IngredientSortType.EXPIRATION_ASC, REFRIGERATOR_ID, IngredientFilter.valueOf(storageType.name()), null, null, BASE_DATE);
        when(ingredientRepository.findExpirationAscPage(REFRIGERATOR_ID, null, null, storageType, null, null,
                null, null, null, null, Limit.of(3))).thenReturn(List.of(expired, notExpired));

        List<Ingredient> rows = ingredientPageReader.read(cursor, 3);

        assertThat(rows).containsExactly(expired, notExpired);
        verifyNoMoreInteractions(ingredientRepository);
    }

    private IngredientListCursor firstCursor(IngredientSortType sortType) {
        return IngredientListCursor.first(sortType, REFRIGERATOR_ID, null, null, null, BASE_DATE);
    }
}
