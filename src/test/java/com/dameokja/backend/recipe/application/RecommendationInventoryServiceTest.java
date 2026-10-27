package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.when;

import com.dameokja.backend.ingredient.application.snapshot.IngredientSnapshotItem;
import com.dameokja.backend.ingredient.application.snapshot.IngredientSnapshotService;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
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

@ExtendWith(MockitoExtension.class)
class RecommendationInventoryServiceTest {
    @Mock private IngredientSnapshotService snapshots;
    private RecommendationInventoryService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationInventoryService(snapshots);
    }

    @Test
    void reusesRequestIdAcrossUsersAndInventoryOrderForTheSameRefrigeratorAndMode() {
        IngredientSnapshotItem first = item(101L, 2);
        IngredientSnapshotItem second = item(102L, 3);
        when(snapshots.getAll(1L, 17L)).thenReturn(List.of(first, second));
        when(snapshots.getAll(2L, 17L)).thenReturn(List.of(second, first));

        AiRecipeRecommendationRequest request = service.prepare(1L, 17L, Mode.INCLUDE_EXPIRED);
        AiRecipeRecommendationRequest repeated = service.prepare(2L, 17L, Mode.INCLUDE_EXPIRED);

        assertThat(repeated.inventoryFingerprint()).isEqualTo(request.inventoryFingerprint());
        assertThat(repeated.requestId()).isEqualTo(request.requestId());
    }

    @Test
    void separatesRequestIdsWhenRefrigeratorModeOrInventoryChanges() {
        when(snapshots.getAll(1L, 17L)).thenReturn(List.of(item(101L, 2)), List.of(item(101L, 2)), List.of(item(101L, 3)));
        when(snapshots.getAll(1L, 18L)).thenReturn(List.of(item(101L, 2)));

        String original = service.prepare(1L, 17L, Mode.INCLUDE_EXPIRED).requestId();

        assertThat(service.prepare(1L, 17L, Mode.EXCLUDE_EXPIRED).requestId()).isNotEqualTo(original);
        assertThat(service.prepare(1L, 17L, Mode.INCLUDE_EXPIRED).requestId()).isNotEqualTo(original);
        assertThat(service.prepare(1L, 18L, Mode.INCLUDE_EXPIRED).requestId()).isNotEqualTo(original);
    }

    @Test
    void includesExpiredInventoryAndFillsBackendDefaultsWithoutChangingOriginalValues() {
        IngredientSnapshotItem expired = item(101L, 2);
        when(snapshots.getAll(1L, 17L)).thenReturn(List.of(expired));

        AiRecipeRecommendationRequest request = service.prepare(1L, 17L, null);

        assertThat(request.recommendationMode()).isEqualTo(Mode.INCLUDE_EXPIRED);
        assertThat(request.limit()).isEqualTo(10);
        assertThat(request.excludedIngredientNames()).isEmpty();
        assertThat(request.inventory()).singleElement().satisfies(item -> {
            assertThat(item.ingredientId()).isEqualTo(101L);
            assertThat(item.name()).isEqualTo("국산 두부");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.expirationDate()).isEqualTo(expired.expirationDate());
        });
    }

    @Test
    void acceptsTheFullThreeHundredItemsAndRejectsOverflowWithoutTruncation() {
        List<IngredientSnapshotItem> inventory = LongStream.rangeClosed(1, 301).mapToObj(id -> item(id, 1)).toList();
        when(snapshots.getAll(1L, 17L)).thenReturn(inventory.subList(0, 300), inventory);

        assertThat(service.prepare(1L, 17L, Mode.INCLUDE_EXPIRED).inventory()).hasSize(300);
        assertThatIllegalArgumentException().isThrownBy(() -> service.prepare(1L, 17L, Mode.INCLUDE_EXPIRED));
    }

    @Test
    void preparesAnEmptyRefrigeratorWithoutInventingInventory() {
        when(snapshots.getAll(1L, 17L)).thenReturn(List.of());

        assertThat(service.prepare(1L, 17L, Mode.INCLUDE_EXPIRED).inventory()).isEmpty();
    }

    private IngredientSnapshotItem item(Long id, int quantity) {
        return new IngredientSnapshotItem(id, "국산 두부", IngredientCategory.TOFU_BEAN, StorageType.FROZEN,
                MeasureType.COUNT, quantity, null, WeightUnit.NONE, LocalDate.of(2026, 1, 1));
    }
}
