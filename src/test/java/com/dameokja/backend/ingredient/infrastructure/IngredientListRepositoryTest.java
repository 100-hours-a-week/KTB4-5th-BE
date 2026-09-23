package com.dameokja.backend.ingredient.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
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
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "EXPIRATION_ASC | 우유,우유,apple,가지 | 양파,두부,Kimchi,123주스",
            "CREATED_DESC   | 가지,우유,우유,apple | 123주스,양파,두부,Kimchi",
            "NAME_ASC       | 가지,우유,우유,apple | 두부,양파,123주스,Kimchi"
    })
    void readsEachGroupInSortOrderAcrossPages(IngredientSortType sortType, String expired, String notExpired) {
        ingredient("우유", -2, 1);
        ingredient("우유", -2, 1);
        ingredient("apple", -2, 1);
        ingredient("가지", -1, 3);
        ingredient("양파", 0, 3);
        ingredient("Kimchi", 3, 2);
        ingredient("두부", 3, 2);
        ingredient("123주스", 7, 4);
        flushAndClear();

        assertThat(readAllPages(sortType, IngredientExpiryGroup.EXPIRED)).containsExactly(expired.split(","));
        assertThat(readAllPages(sortType, IngredientExpiryGroup.NOT_EXPIRED)).containsExactly(notExpired.split(","));
    }

    @Test
    void excludesOtherRefrigerators() {
        ingredient("우유", 1, 1);
        Refrigerator other = persist(new Refrigerator("다른냉장고", "2026-09"));
        persist(new Ingredient(other, details("두부", 1), RegistrationSource.DIRECT));
        flushAndClear();

        List<Ingredient> page = ingredientRepository.findListPage(IngredientSortType.EXPIRATION_ASC,
                IngredientPageCondition.of(refrigerator.getId(), IngredientExpiryGroup.NOT_EXPIRED, BASE_DATE, null), ALL);

        assertThat(page).extracting(Ingredient::getName).containsExactly("우유");
    }

    // 한 건씩 넘겨 모든 커서 경계(동률 행 사이 포함)를 지나게 한다.
    private List<String> readAllPages(IngredientSortType sortType, IngredientExpiryGroup group) {
        List<String> names = new ArrayList<>();
        IngredientCursor cursor = null;
        List<Ingredient> page = findPage(sortType, group, cursor);
        while (!page.isEmpty()) {
            names.add(page.getFirst().getName());
            cursor = IngredientCursor.from(page.getFirst());
            page = findPage(sortType, group, cursor);
        }
        return names;
    }

    private List<Ingredient> findPage(IngredientSortType sortType, IngredientExpiryGroup group, IngredientCursor cursor) {
        IngredientPageCondition condition = IngredientPageCondition.of(refrigerator.getId(), group, BASE_DATE, cursor);
        return ingredientRepository.findListPage(sortType, condition, 1);
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
