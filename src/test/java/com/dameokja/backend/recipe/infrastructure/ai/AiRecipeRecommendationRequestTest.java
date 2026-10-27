package com.dameokja.backend.recipe.infrastructure.ai;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AiRecipeRecommendationRequestTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void matchesPythonFingerprintFixtures() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/recipe-ai/fingerprint-fixtures.json")) {
            for (JsonNode fixture : mapper.readTree(input)) {
                List<AiRecipeInventoryItem> inventory = mapper.readerForListOf(AiRecipeInventoryItem.class).readValue(fixture.path("inventory"));
                AiRecipeRecommendationRequest request = AiRecipeRecommendationRequest.create("req-1", 17L,
                        AiRecipeRecommendationRequest.Mode.EXCLUDE_EXPIRED, inventory, List.of(), 10);
                assertThat(request.inventoryFingerprint()).as(fixture.path("case").asText()).isEqualTo(fixture.path("fingerprint").asText());
            }
        }
    }

    @Test
    void mapsPersistedInventoryWithoutChangingItsNameOrMeasurement() {
        Refrigerator refrigerator = org.mockito.Mockito.mock(Refrigerator.class);
        org.mockito.Mockito.when(refrigerator.getId()).thenReturn(17L);
        Ingredient ingredient = new Ingredient(refrigerator,
                new IngredientDetails("국산 두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                        Measurement.of(MeasureType.COUNT, 2, null, WeightUnit.NONE), LocalDate.of(2026, 11, 1)),
                RegistrationSource.DIRECT, org.mockito.Mockito.mock(User.class));
        ReflectionTestUtils.setField(ingredient, "id", 101L);
        AiRecipeRecommendationRequest request = AiRecipeRecommendationRequest.from("req-1", 17L,
                AiRecipeRecommendationRequest.Mode.EXCLUDE_EXPIRED, List.of(ingredient), List.of(), 10);
        assertThat(request.inventory().getFirst()).extracting(AiRecipeInventoryItem::ingredientId, AiRecipeInventoryItem::name,
                AiRecipeInventoryItem::quantity, AiRecipeInventoryItem::weightValue).containsExactly(101L, "국산 두부", 2, null);
        assertThatIllegalArgumentException().isThrownBy(() -> AiRecipeRecommendationRequest.from("req-1", 18L,
                AiRecipeRecommendationRequest.Mode.EXCLUDE_EXPIRED, List.of(ingredient), List.of(), 10));
    }

    @Test
    void rejectsOversizedOrDuplicateInventoryRatherThanSilentlyTruncatingIt() {
        AiRecipeInventoryItem item = new AiRecipeInventoryItem(101L, "두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                MeasureType.COUNT, 2, null, WeightUnit.NONE, LocalDate.of(2026, 11, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> AiRecipeRecommendationRequest.create("req-1", 17L,
                AiRecipeRecommendationRequest.Mode.EXCLUDE_EXPIRED, java.util.Collections.nCopies(301, item), List.of(), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> AiRecipeRecommendationRequest.create("req-1", 17L,
                AiRecipeRecommendationRequest.Mode.EXCLUDE_EXPIRED, List.of(item, item), List.of(), 10));
    }
}
