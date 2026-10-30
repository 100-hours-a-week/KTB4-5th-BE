package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.recipe.application.RecommendationListResult.IngredientCounts;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RecommendationRecipeSummaryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void countsDistinctIngredientNumbersAcrossDifferentlyNamedStocks() throws Exception {
        ObjectNode recipeSnapshot = recipeSnapshot();
        ArrayNode inventoryUsage = (ArrayNode) recipeSnapshot.path("inventoryUsage");
        inventoryUsage.add(((ObjectNode) inventoryUsage.get(0)).deepCopy().put("ingredientId", 102L));
        Recipe recipe = jsonMapper.treeToValue(recipeSnapshot, Recipe.class);
        AiRecipeInventoryItem expiringStock = stock(101L, "국산 두부", TODAY);
        AiRecipeInventoryItem otherStock = stock(102L, "큰 두부", TODAY.plusDays(7));

        assertThat(RecommendationRecipeSummary.from(recipe, Map.of(101L, expiringStock, 102L, otherStock), TODAY).ingredientCounts())
                .isEqualTo(new IngredientCounts(1, 1, 1));
        assertThat(RecommendationRecipeSummary.from(recipe, Map.of(102L, otherStock), TODAY).ingredientCounts())
                .isEqualTo(new IngredientCounts(1, 1, 0));
        assertThat(RecommendationRecipeSummary.from(recipe, Map.of(), TODAY).ingredientCounts())
                .isEqualTo(new IngredientCounts(1, 0, 0));
    }

    @Test
    void doesNotGuessOwnershipFromNamesWhenIngredientMappingIsUnresolved() throws Exception {
        ObjectNode recipeSnapshot = recipeSnapshot();
        ((ObjectNode) recipeSnapshot.path("inventoryUsage").get(0)).put("allocationStatus", "UNRESOLVED").putArray("allocations");
        Recipe recipe = jsonMapper.treeToValue(recipeSnapshot, Recipe.class);

        assertThat(RecommendationRecipeSummary.from(recipe, Map.of(101L, stock(101L, "두부", TODAY)), TODAY).ingredientCounts())
                .isEqualTo(new IngredientCounts(1, null, null));
    }

    private ObjectNode recipeSnapshot() throws Exception {
        return (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json")).path("recommendations").get(0);
    }

    private AiRecipeInventoryItem stock(Long ingredientId, String name, LocalDate expirationDate) {
        return new AiRecipeInventoryItem(ingredientId, name, IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                MeasureType.COUNT, 1, null, WeightUnit.NONE, expirationDate);
    }
}
