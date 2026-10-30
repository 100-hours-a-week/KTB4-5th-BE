package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.when;

import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RecommendationInventoryServiceTest {
    @Mock private IngredientListService ingredients;
    private RecommendationInventoryService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationInventoryService(ingredients);
    }

    @Test
    void createsNewRequestIdEvenWhenRefrigeratorInventoryAndModeAreUnchanged() {
        Ingredient first = item(101L, 2);
        Ingredient second = item(102L, 3);
        when(ingredients.getAll(1L, 17L)).thenReturn(List.of(first, second));
        when(ingredients.getAll(2L, 17L)).thenReturn(List.of(second, first));

        AiRecipeRecommendationRequest request = service.prepare(1L, 17L, options(Mode.INCLUDE_EXPIRED));
        AiRecipeRecommendationRequest repeated = service.prepare(2L, 17L, options(Mode.INCLUDE_EXPIRED));

        assertThat(repeated.inventoryFingerprint()).isEqualTo(request.inventoryFingerprint());
        assertThat(repeated.requestId()).isNotEqualTo(request.requestId());
    }

    @Test
    void keepsOriginalInventoryAndOmitsUnsetOptionalInputs() {
        Ingredient expired = item(101L, 2);
        when(ingredients.getAll(1L, 17L)).thenReturn(List.of(expired));

        AiRecipeRecommendationRequest request = service.prepare(1L, 17L, null);

        assertThat(request.recommendationMode()).isNull();
        assertThat(request.limit()).isNull();
        assertThat(request.excludedIngredientNames()).isNull();
        assertThat(request.inventory()).singleElement().satisfies(item -> {
            assertThat(item.ingredientId()).isEqualTo(101L);
            assertThat(item.name()).isEqualTo("국산 두부");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.expirationDate()).isEqualTo(expired.getExpirationDate());
        });
    }

    @Test
    void acceptsTheFullThreeHundredItemsAndRejectsOverflowWithoutTruncation() {
        List<Ingredient> inventory = LongStream.rangeClosed(1, 301).mapToObj(id -> item(id, 1)).toList();
        when(ingredients.getAll(1L, 17L)).thenReturn(inventory.subList(0, 300), inventory);

        assertThat(service.prepare(1L, 17L, options(Mode.INCLUDE_EXPIRED)).inventory()).hasSize(300);
        assertThatIllegalArgumentException().isThrownBy(() -> service.prepare(1L, 17L, options(Mode.INCLUDE_EXPIRED)));
    }

    @Test
    void preparesAnEmptyRefrigeratorWithoutInventingInventory() {
        when(ingredients.getAll(1L, 17L)).thenReturn(List.of());

        assertThat(service.prepare(1L, 17L, options(Mode.INCLUDE_EXPIRED)).inventory()).isEmpty();
    }

    private RecommendationOptions options(Mode recommendationMode) {
        return new RecommendationOptions(recommendationMode, null, null, null, null, null, null, null);
    }

    private Ingredient item(Long id, int quantity) {
        Ingredient ingredient = new Ingredient(new Refrigerator("냉장고", "2026-10"),
                new IngredientDetails("국산 두부", IngredientCategory.TOFU_BEAN, StorageType.FROZEN,
                        Measurement.of(MeasureType.COUNT, quantity, null, WeightUnit.NONE), LocalDate.of(2026, 1, 1)),
                RegistrationSource.DIRECT, new User("등록자", "test"));
        ReflectionTestUtils.setField(ingredient, "id", id);
        return ingredient;
    }
}
