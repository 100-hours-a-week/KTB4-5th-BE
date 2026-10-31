package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.recipe.application.RecommendationStockAllocator.StockAllocation;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RecommendationStockAllocatorTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void recalculatesFromCurrentStockInResponseOrderInsteadOfOldUsageAmounts() throws Exception {
        Recipe recipe = recipe("300", "G", 101L, 102L);
        Map<Long, AiRecipeInventoryItem> inventory = Map.of(101L, weightStock(101L, "국산 두부", "120"),
                102L, weightStock(102L, "큰 두부", "500"));

        List<StockAllocation> allocations = RecommendationStockAllocator.allocate(recipe, inventory).get(1);

        assertThat(allocations).extracting(StockAllocation::ingredientId).containsExactly(101L, 102L);
        assertThat(allocations.get(0).usedAmount()).isEqualByComparingTo("120");
        assertThat(allocations.get(1).usedAmount()).isEqualByComparingTo("180");
        assertThat(allocations.get(1).ownedAmount()).isEqualByComparingTo("500");
        List<StockAllocation> reduced = RecommendationStockAllocator.allocate(recipe,
                Map.of(101L, weightStock(101L, "국산 두부", "50"), 102L, weightStock(102L, "큰 두부", "100"))).get(1);
        assertThat(reduced.get(0).usedAmount()).isEqualByComparingTo("50");
        assertThat(reduced.get(1).usedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void doesNotAllocateTheSameStockTwiceAcrossRecipeIngredients() throws Exception {
        ObjectNode recipeSnapshot = snapshot("200", "G", 101L);
        ArrayNode ingredients = (ArrayNode) recipeSnapshot.path("ingredients");
        ingredients.add(((ObjectNode) ingredients.get(0)).deepCopy().put("ingredientNo", 2));
        ArrayNode requirements = (ArrayNode) recipeSnapshot.path("ingredientRequirements");
        requirements.add(((ObjectNode) requirements.get(0)).deepCopy().put("ingredientNo", 2));
        ((ArrayNode) recipeSnapshot.path("inventoryUsage").get(0).path("allocations")).addObject().put("ingredientNo", 2);
        Recipe recipe = jsonMapper.treeToValue(recipeSnapshot, Recipe.class);

        Map<Integer, List<StockAllocation>> allocations = RecommendationStockAllocator.allocate(recipe, Map.of(101L, weightStock(101L, "두부", "300")));

        assertThat(allocations.get(1).getFirst().usedAmount()).isEqualByComparingTo("200");
        assertThat(allocations.get(2).getFirst().usedAmount()).isEqualByComparingTo("100");
        assertThat(allocations.get(2).getFirst().ownedAmount()).isEqualByComparingTo("300");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EA", "G", "ML"})
    void resolvesConfirmedMappingToTheClosestWholeQuantity(String unit) throws Exception {
        ObjectNode recipeSnapshot = snapshot("2.6", unit, 101L);
        ((ObjectNode) recipeSnapshot.path("inventoryUsage").get(0)).put("allocationStatus", "UNRESOLVED");
        Recipe recipe = jsonMapper.treeToValue(recipeSnapshot, Recipe.class);

        AiRecipeInventoryItem stock = countStock(101L, 5);
        if (!"EA".equals(unit)) {
            stock = new AiRecipeInventoryItem(101L, "두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                    MeasureType.WEIGHT, null, new BigDecimal("5"), WeightUnit.valueOf(unit), LocalDate.of(2026, 11, 3));
        }
        StockAllocation allocation = RecommendationStockAllocator.allocate(recipe, Map.of(101L, stock)).get(1).getFirst();

        assertThat(allocation.usedAmount()).isEqualByComparingTo("3");
        assertThat(allocation.ownedAmount()).isEqualByComparingTo("5");
        assertThat(allocation.unit()).isEqualTo(unit);
        assertThat(allocation.allocationStatus()).isEqualTo("CALCULATED");
    }

    @Test
    void keepsConfirmedStockVisibleWhenUnitsCannotBeConvertedOrRequirementIsUnknown() throws Exception {
        StockAllocation incompatible = RecommendationStockAllocator.allocate(recipe("300", "G", 101L), Map.of(101L, countStock(101L, 2))).get(1).getFirst();
        StockAllocation unknown = RecommendationStockAllocator.allocate(recipe(null, "G", 101L), Map.of(101L, weightStock(101L, "두부", "300"))).get(1).getFirst();

        assertThat(incompatible.usedAmount()).isNull();
        assertThat(incompatible.ownedAmount()).isEqualByComparingTo("2");
        assertThat(incompatible.unit()).isEqualTo("EA");
        assertThat(incompatible.expirationDate()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(incompatible.allocationStatus()).isEqualTo("UNRESOLVED");
        assertThat(unknown.usedAmount()).isNull();
        assertThat(unknown.allocationStatus()).isEqualTo("UNRESOLVED");
    }

    @Test
    void omitsDeletedStockAndDoesNotInferMissingMappingsFromMatchingNames() throws Exception {
        Map<Long, AiRecipeInventoryItem> unrelatedStock = Map.of(999L, weightStock(999L, "두부", "300"));
        assertThat(RecommendationStockAllocator.allocate(recipe("300", "G", 101L), unrelatedStock).get(1)).isEmpty();
        ObjectNode recipeSnapshot = snapshot("300", "G", 101L);
        ((ObjectNode) recipeSnapshot.path("inventoryUsage").get(0)).put("allocationStatus", "UNRESOLVED").putArray("allocations");
        Recipe recipe = jsonMapper.treeToValue(recipeSnapshot, Recipe.class);

        assertThat(RecommendationStockAllocator.allocate(recipe, Map.of(101L, weightStock(101L, "두부", "300"))).get(1)).isEmpty();
    }

    private Recipe recipe(String requiredAmount, String unit, Long... ingredientIds) throws Exception {
        return jsonMapper.treeToValue(snapshot(requiredAmount, unit, ingredientIds), Recipe.class);
    }

    private ObjectNode snapshot(String requiredAmount, String unit, Long... ingredientIds) throws Exception {
        ObjectNode recipe = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json")).path("recommendations").get(0);
        ObjectNode requirement = (ObjectNode) recipe.path("ingredientRequirements").get(0);
        requirement.put("unit", unit);
        if (requiredAmount == null) { requirement.putNull("requiredAmount"); }
        else { requirement.put("requiredAmount", new BigDecimal(requiredAmount)); }
        ObjectNode usageTemplate = ((ObjectNode) recipe.path("inventoryUsage").get(0)).deepCopy();
        ArrayNode usages = recipe.putArray("inventoryUsage");
        for (Long ingredientId : ingredientIds) { usages.add(usageTemplate.deepCopy().put("ingredientId", ingredientId)); }
        return recipe;
    }

    private AiRecipeInventoryItem weightStock(Long ingredientId, String name, String amount) {
        return new AiRecipeInventoryItem(ingredientId, name, IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                MeasureType.WEIGHT, null, new BigDecimal(amount), WeightUnit.G, LocalDate.of(2026, 11, 3));
    }

    private AiRecipeInventoryItem countStock(Long ingredientId, int quantity) {
        return new AiRecipeInventoryItem(ingredientId, "두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                MeasureType.COUNT, quantity, null, WeightUnit.NONE, LocalDate.of(2026, 11, 3));
    }
}
