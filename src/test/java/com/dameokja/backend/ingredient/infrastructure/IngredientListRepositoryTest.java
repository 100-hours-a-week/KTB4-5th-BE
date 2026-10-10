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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

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
            "EXPIRATION_ASC | apple,우유,우유,가지,양파,Kimchi,두부,123주스",
            "CREATED_DESC   | 123주스,가지,양파,Kimchi,두부,apple,우유,우유",
            "NAME_ASC       | 123주스,apple,Kimchi,가지,두부,양파,우유,우유"
    })
    void readsWholeListInSortOrderAcrossPages(IngredientSortType sortType, String expected) {
        // 같은 품목은 한 행으로 합산되므로, 보관 방식만 다르고 정렬 키가 모두 같은 두 행으로 ID 순서를 검증한다.
        ingredient("우유", -2, 1);
        ingredient("우유", -2, 1, StorageType.FROZEN);
        ingredient("apple", -2, 1);
        ingredient("가지", -1, 3);
        ingredient("양파", 0, 3);
        ingredient("Kimchi", 3, 2);
        ingredient("두부", 3, 2);
        ingredient("123주스", 7, 4);
        flushAndClear();

        assertThat(readAllPages(sortType, null, null, null, null)).containsExactly(expected.split(","));
    }

    @ParameterizedTest
    @EnumSource(IngredientSortType.class)
    void sortsNumericEnglishAndKoreanNamesConsistentlyAcrossPages(IngredientSortType sortType) {
        // 날짜와 등록 시각을 같게 맞춰 하위 기준의 이름 비교도 검증한다.
        for (int daysUntilExpiration : List.of(-1, 1)) {
            ingredient("우유", daysUntilExpiration, 1);
            ingredient("Kimchi", daysUntilExpiration, 1);
            ingredient("123주스", daysUntilExpiration, 1);
            ingredient("apple", daysUntilExpiration, 1);
            ingredient("가지", daysUntilExpiration, 1);
            ingredient("123주스", daysUntilExpiration, 1, StorageType.FROZEN);
        }
        flushAndClear();

        assertThat(readAllPages(sortType, null, BASE_DATE.minusDays(1), null, null))
                .containsExactly("123주스", "123주스", "apple", "Kimchi", "가지", "우유");
        assertThat(readAllPages(sortType, BASE_DATE, null, null, null))
                .containsExactly("123주스", "123주스", "apple", "Kimchi", "가지", "우유");
    }

    @Test
    void excludesOtherRefrigerators() {
        ingredient("우유", 1, 1);
        Refrigerator other = persist(new Refrigerator("다른냉장고", "2026-09"));
        persist(new Ingredient(other, details("두부", 1), RegistrationSource.DIRECT));
        flushAndClear();

        List<Ingredient> page = ingredientRepository.findExpirationAscPage(
                refrigerator.getId(), BASE_DATE, null, null, null, null, null, null, null, Limit.of(ALL));

        assertThat(page).extracting(Ingredient::getName).containsExactly("우유");
    }

    @Test
    void narrowsByInclusiveExpirationRangeAndStorageType() {
        ingredient("어제", -1, 1);
        ingredient("오늘", 0, 1);
        ingredient("사흘뒤", 3, 1);
        ingredient("나흘뒤", 4, 1);
        persist(new Ingredient(refrigerator, new IngredientDetails("냉동만두", IngredientCategory.OTHER, StorageType.FROZEN,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), BASE_DATE), RegistrationSource.DIRECT));
        flushAndClear();

        assertThat(names(BASE_DATE, BASE_DATE.plusDays(3), null)).containsExactly("냉동만두", "사흘뒤", "오늘");
        assertThat(names(BASE_DATE.plusDays(4), null, null)).containsExactly("나흘뒤");
        assertThat(names(null, null, StorageType.FROZEN)).containsExactly("냉동만두");
    }

    @Test
    void countsIngredientsMatchingFilter() {
        ingredient("어제", -1, 1);
        ingredient("오늘", 0, 1);
        ingredient("나흘뒤", 4, 1);
        persist(new Ingredient(refrigerator, new IngredientDetails("냉동만두", IngredientCategory.OTHER, StorageType.FROZEN,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), BASE_DATE), RegistrationSource.DIRECT));
        Refrigerator other = persist(new Refrigerator("다른냉장고", "2026-09"));
        persist(new Ingredient(other, details("두부", 0), RegistrationSource.DIRECT));
        flushAndClear();

        Long refrigeratorId = refrigerator.getId();
        assertThat(ingredientRepository.countFiltered(refrigeratorId, null, null, null, null)).isEqualTo(4L);
        assertThat(ingredientRepository.countFiltered(refrigeratorId, null, BASE_DATE.minusDays(1), null, null)).isEqualTo(1L);
        assertThat(ingredientRepository.countFiltered(refrigeratorId, BASE_DATE, BASE_DATE.plusDays(3), null, null)).isEqualTo(2L);
        assertThat(ingredientRepository.countFiltered(refrigeratorId, null, null, StorageType.FROZEN, null)).isEqualTo(1L);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"EXPIRATION_ASC | A,B", "CREATED_DESC | B,A", "NAME_ASC | A,B"})
    void combinesCategoryWithDateAndStorageAcrossPages(IngredientSortType sortType, String expected) {
        ingredient("B", 1, 2, StorageType.FROZEN);
        ingredient("A", 0, 1, StorageType.FROZEN);
        ingredient("냉장", 0, 1);
        ingredient("만료", -1, 1, StorageType.FROZEN);
        persist(new Ingredient(refrigerator, new IngredientDetails("채소", IngredientCategory.VEGETABLE, StorageType.FROZEN,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), BASE_DATE), RegistrationSource.DIRECT));
        Refrigerator other = persist(new Refrigerator("다른냉장고", "2026-09"));
        persist(new Ingredient(other, details("타냉장고", 0, StorageType.FROZEN), RegistrationSource.DIRECT));
        flushAndClear();

        assertThat(readAllPages(sortType, BASE_DATE, BASE_DATE.plusDays(3), StorageType.FROZEN, IngredientCategory.OTHER))
                .containsExactly(expected.split(","));
        assertThat(ingredientRepository.countFiltered(refrigerator.getId(), BASE_DATE, BASE_DATE.plusDays(3),
                StorageType.FROZEN, IngredientCategory.OTHER)).isEqualTo(2L);
        assertThat(ingredientRepository.countFiltered(refrigerator.getId(), null, null, null, IngredientCategory.OTHER)).isEqualTo(4L);
        assertThat(ingredientRepository.countFiltered(refrigerator.getId(), null, null, null, IngredientCategory.FRUIT)).isZero();
        assertThat(readAllPages(sortType, null, null, null, IngredientCategory.FRUIT)).isEmpty();
        assertThat(ingredientRepository.countByRefrigeratorId(refrigerator.getId())).isEqualTo(5L);
    }

    private List<String> names(LocalDate from, LocalDate to, StorageType storageType) {
        return ingredientRepository.findNameAscPage(refrigerator.getId(), from, to, storageType, null,
                        null, null, null, null, Limit.of(ALL)).stream()
                .map(Ingredient::getName)
                .toList();
    }

    // 한 건씩 넘겨 모든 커서 경계(동률 행 사이 포함)를 지나게 한다.
    private List<String> readAllPages(IngredientSortType sortType, LocalDate from, LocalDate to,
                                      StorageType storageType, IngredientCategory category) {
        List<String> names = new ArrayList<>();
        IngredientCursor cursor = null;
        List<Ingredient> page = findPage(sortType, from, to, storageType, category, cursor);
        while (!page.isEmpty()) {
            names.add(page.getFirst().getName());
            cursor = IngredientCursor.from(page.getFirst());
            page = findPage(sortType, from, to, storageType, category, cursor);
        }
        return names;
    }

    private List<Ingredient> findPage(IngredientSortType sortType, LocalDate from, LocalDate to,
                                      StorageType storageType, IngredientCategory category, IngredientCursor cursor) {
        Long refrigeratorId = refrigerator.getId();
        LocalDate expirationDate = cursor == null ? null : cursor.expirationDate();
        LocalDateTime createdAt = cursor == null ? null : cursor.createdAt();
        String name = cursor == null ? null : cursor.name();
        Long id = cursor == null ? null : cursor.ingredientId();
        Limit one = Limit.of(1);
        return switch (sortType) {
            case EXPIRATION_ASC -> ingredientRepository.findExpirationAscPage(
                    refrigeratorId, from, to, storageType, category, expirationDate, createdAt, name, id, one);
            case CREATED_DESC -> ingredientRepository.findCreatedDescPage(
                    refrigeratorId, from, to, storageType, category, expirationDate, createdAt, name, id, one);
            case NAME_ASC -> ingredientRepository.findNameAscPage(
                    refrigeratorId, from, to, storageType, category, expirationDate, createdAt, name, id, one);
        };
    }

    private void ingredient(String name, int daysUntilExpiration, int createdDay) {
        ingredient(name, daysUntilExpiration, createdDay, StorageType.REFRIGERATED);
    }

    private void ingredient(String name, int daysUntilExpiration, int createdDay, StorageType storageType) {
        Ingredient ingredient = persist(new Ingredient(
                refrigerator, details(name, daysUntilExpiration, storageType), RegistrationSource.DIRECT));
        entityManager.flush();
        entityManager.createNativeQuery("update ingredients set created_at = ?1 where ingredient_id = ?2")
                .setParameter(1, BASE_TIME.plusDays(createdDay))
                .setParameter(2, ingredient.getId())
                .executeUpdate();
    }

    private IngredientDetails details(String name, int daysUntilExpiration) {
        return details(name, daysUntilExpiration, StorageType.REFRIGERATED);
    }

    private IngredientDetails details(String name, int daysUntilExpiration, StorageType storageType) {
        return new IngredientDetails(name, IngredientCategory.OTHER, storageType,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE),
                BASE_DATE.plusDays(daysUntilExpiration));
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
