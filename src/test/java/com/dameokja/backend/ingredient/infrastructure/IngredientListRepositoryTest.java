package com.dameokja.backend.ingredient.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

class IngredientListRepositoryTest extends MySqlJpaTest {
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 23);
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 20, 0, 0);
    private static final int ALL = 100;

    @Autowired
    IngredientRepository ingredientRepository;

    private Refrigerator refrigerator;

    @BeforeEach
    void setUp() {
        refrigerator = persist(new Refrigerator("냉장고", "2026-09"));
    }

    @Test
    void sortsByExpirationThenNewestCreated() {
        ingredient("우유", -1, 1);
        ingredient("달걀", 2, 1);
        ingredient("두부", 2, 5);
        flushAndClear();

        assertThat(names(IngredientSortType.EXPIRATION_ASC)).containsExactly("우유", "두부", "달걀");
    }

    @Test
    void sortsNewestCreatedAfterExpiredGroup() {
        ingredient("오래된만료", -1, 1);
        ingredient("최근등록", 10, 9);
        ingredient("중간등록", 5, 5);
        flushAndClear();

        assertThat(names(IngredientSortType.CREATED_DESC)).containsExactly("오래된만료", "최근등록", "중간등록");
    }

    @Test
    void sortsKoreanNamesBeforeOthersAfterExpiredGroup() {
        ingredient("bacon", -1, 1);
        ingredient("Apple", 5, 1);
        ingredient("123주스", 5, 1);
        ingredient("우유", 5, 1);
        ingredient("ㅎ간장", 5, 1);
        ingredient("가지", 5, 1);
        flushAndClear();

        assertThat(names(IngredientSortType.NAME_ASC))
                .containsExactly("bacon", "가지", "우유", "ㅎ간장", "123주스", "Apple");
    }

    @ParameterizedTest
    @EnumSource(IngredientSortType.class)
    void continuesFromCursorWithoutGapOrDuplicate(IngredientSortType sortType) {
        ingredient("두부", -2, 1);
        ingredient("두부", -2, 1);
        ingredient("apple", -1, 3);
        ingredient("양파", 0, 3);
        ingredient("양파", 3, 2);
        ingredient("Kimchi", 3, 2);
        ingredient("가지", 7, 4);
        flushAndClear();

        List<Long> expected = ids(ingredientRepository.findListPage(query(sortType, null, ALL)));
        assertThat(readAllPages(sortType, 2)).containsExactlyElementsOf(expected);
    }

    @Test
    void excludesOtherRefrigerators() {
        ingredient("우유", 1, 1);
        Refrigerator other = persist(new Refrigerator("다른냉장고", "2026-09"));
        persist(new Ingredient(other, details("두부", 1), RegistrationSource.DIRECT));
        flushAndClear();

        assertThat(names(IngredientSortType.EXPIRATION_ASC)).containsExactly("우유");
    }

    private List<Long> readAllPages(IngredientSortType sortType, int size) {
        List<Long> result = new ArrayList<>();
        IngredientCursor cursor = null;
        List<Ingredient> page = ingredientRepository.findListPage(query(sortType, cursor, size));
        while (!page.isEmpty()) {
            result.addAll(ids(page));
            cursor = IngredientCursor.from(page.getLast());
            page = ingredientRepository.findListPage(query(sortType, cursor, size));
        }
        return result;
    }

    private IngredientListQuery query(IngredientSortType sortType, IngredientCursor cursor, int limit) {
        return new IngredientListQuery(refrigerator.getId(), sortType, BASE_DATE, cursor, limit);
    }

    private List<String> names(IngredientSortType sortType) {
        return ingredientRepository.findListPage(query(sortType, null, ALL)).stream()
                .map(Ingredient::getName)
                .toList();
    }

    private List<Long> ids(List<Ingredient> ingredients) {
        return ingredients.stream().map(Ingredient::getId).toList();
    }

    private void ingredient(String name, int daysUntilExpiration, int createdDay) {
        Ingredient ingredient = persist(new Ingredient(
                refrigerator, details(name, daysUntilExpiration), RegistrationSource.DIRECT));
        entityManager.flush();
        entityManager.createNativeQuery("update ingredients set created_at = ?1 where ingredient_id = ?2")
                .setParameter(1, BASE_TIME.plusDays(createdDay))
                .setParameter(2, ingredient.getId())
                .executeUpdate();
    }

    private IngredientDetails details(String name, int daysUntilExpiration) {
        return new IngredientDetails(name, IngredientCategory.OTHER, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE),
                BASE_DATE.plusDays(daysUntilExpiration));
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
