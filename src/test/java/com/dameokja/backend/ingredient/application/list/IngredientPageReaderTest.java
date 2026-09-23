package com.dameokja.backend.ingredient.application.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    void fillsRemainingRowsFromNotExpiredGroupWhenExpiredGroupRunsOut() {
        Ingredient expired = mock(Ingredient.class);
        Ingredient notExpired = mock(Ingredient.class);
        when(ingredientRepository.findExpirationAscPage(REFRIGERATOR_ID, true, BASE_DATE, null, null, null, null, Limit.of(3)))
                .thenReturn(List.of(expired));
        when(ingredientRepository.findExpirationAscPage(REFRIGERATOR_ID, false, BASE_DATE, null, null, null, null, Limit.of(2)))
                .thenReturn(List.of(notExpired));

        List<Ingredient> rows = ingredientPageReader.read(firstCursor(IngredientSortType.EXPIRATION_ASC), 3);

        assertThat(rows).containsExactly(expired, notExpired);
    }

    @Test
    void readsOnlyExpiredGroupWhenItFillsTheLimit() {
        List<Ingredient> expired = List.of(mock(Ingredient.class), mock(Ingredient.class));
        when(ingredientRepository.findCreatedDescPage(REFRIGERATOR_ID, true, BASE_DATE, null, null, null, null, Limit.of(2)))
                .thenReturn(expired);

        List<Ingredient> rows = ingredientPageReader.read(firstCursor(IngredientSortType.CREATED_DESC), 2);

        assertThat(rows).isEqualTo(expired);
        verifyNoMoreInteractions(ingredientRepository);
    }

    @Test
    void continuesNotExpiredGroupAfterCursorPosition() {
        IngredientCursor position = new IngredientCursor(BASE_DATE, LocalDateTime.of(2026, 9, 20, 9, 0), "두부", 7L);
        IngredientListCursor cursor = new IngredientListCursor(IngredientSortType.NAME_ASC, REFRIGERATOR_ID, BASE_DATE,
                IngredientExpiryGroup.NOT_EXPIRED, position);
        List<Ingredient> notExpired = List.of(mock(Ingredient.class));
        when(ingredientRepository.findNameAscPage(REFRIGERATOR_ID, false, BASE_DATE, BASE_DATE,
                position.createdAt(), "두부", 7L, Limit.of(5))).thenReturn(notExpired);

        List<Ingredient> rows = ingredientPageReader.read(cursor, 5);

        assertThat(rows).isEqualTo(notExpired);
        verifyNoMoreInteractions(ingredientRepository);
    }

    private IngredientListCursor firstCursor(IngredientSortType sortType) {
        return IngredientListCursor.first(sortType, REFRIGERATOR_ID, BASE_DATE);
    }
}
