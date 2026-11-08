package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RecommendationFallbackTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final RecommendationStateRepository states = mock(RecommendationStateRepository.class);
    private final RecommendationListRepository lists = mock(RecommendationListRepository.class);
    private final IngredientListService ingredients = mock(IngredientListService.class);
    private final RefrigeratorAccessService access = mock(RefrigeratorAccessService.class);
    private final RecommendationDailyQuotaService quota = mock(RecommendationDailyQuotaService.class);
    private final RecommendationState state = new RecommendationState(null, null);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"), ZoneOffset.UTC);
    private final RecommendationListService listService = new RecommendationListService(ingredients, states,
            mock(RecommendationOutboxRepository.class), quota, jsonMapper, clock);
    private final RecommendationDetailService detailService = new RecommendationDetailService(states, lists, ingredients, access, jsonMapper, clock);
    private ObjectNode snapshot;
    private ObjectNode recipe;
    private RecommendationList selectedList;

    @BeforeEach
    void prepareSelectedSnapshot() throws Exception {
        snapshot = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
        recipe = ((ObjectNode) snapshot.path("recommendations").get(0)).deepCopy();
        snapshot.putArray("recommendations");
        snapshot.putArray("confirmationCandidates");
        when(states.findByUserIdAndRefrigeratorId(1L, 2L)).thenReturn(Optional.of(state));
        when(ingredients.getByIds(eq(1L), eq(2L), anyList())).thenReturn(List.of());
        when(ingredients.getByIdsForUpdate(eq(2L), anyList())).thenReturn(List.of());
    }

    @Test
    void appendsConfirmationCandidatesInOrderWithoutChangingStoredSnapshot() {
        add("recommendations", "1", "추천");
        add("confirmationCandidates", "2", "확인 후보");
        String storedSnapshot = store();

        assertThat(listService.get(1L, 2L, 10).recipes()).extracting(RecommendationListResult.RecipeSummary::name)
                .containsExactly("추천", "확인 후보");
        assertThat(selectedList.getResultJson()).isEqualTo(storedSnapshot);
    }

    @Test
    void keepsRecommendedVersionAndDeduplicatesConfirmationCandidatesByRecipeId() {
        add("recommendations", "1", "추천");
        add("confirmationCandidates", "1", "중복 후보");
        add("confirmationCandidates", "2", "첫 후보");
        add("confirmationCandidates", "2", "중복 후보");
        add("confirmationCandidates", "1", "다른 원천").put("source", "OTHER_SOURCE");
        store();

        assertThat(listService.get(1L, 2L, 10).recipes()).extracting(RecommendationListResult.RecipeSummary::name)
                .containsExactly("추천", "첫 후보", "다른 원천");
    }

    @Test
    void fillsAnEmptyRecommendationListFromConfirmationCandidates() {
        add("confirmationCandidates", "1", "확인 후보");
        store();

        assertThat(listService.get(1L, 2L, 10).recipes()).extracting(RecommendationListResult.RecipeSummary::name).containsExactly("확인 후보");
    }

    @Test
    void respectsRequestedLimitAndKeepsExistingRecommendationsFirst() {
        add("recommendations", "1", "첫 추천");
        add("recommendations", "2", "둘째 추천");
        add("confirmationCandidates", "3", "확인 후보");
        store();

        assertThat(listService.get(1L, 2L, 1).recipes()).extracting(RecommendationListResult.RecipeSummary::name).containsExactly("첫 추천");
        assertThat(listService.get(1L, 2L, 2).recipes()).extracting(RecommendationListResult.RecipeSummary::name).containsExactly("첫 추천", "둘째 추천");
    }

    @Test
    void capsTheSharedSelectionAtTenAndRejectsOverflowInDetailLookup() {
        add("recommendations", "1", "추천");
        IntStream.rangeClosed(2, 12).forEach(index -> add("confirmationCandidates", Integer.toString(index), "후보" + index));
        store();

        assertThat(listService.get(1L, 2L, 10).recipes()).hasSize(10)
                .extracting(RecommendationListResult.RecipeSummary::recipeId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 10).mapToObj(index -> "FOOD_SAFETY_KOREA:" + index).toList());
        assertThat(detailService.get(1L, 2L, "FOOD_SAFETY_KOREA:10").name()).isEqualTo("후보10");
        assertThatThrownBy(() -> detailService.get(1L, 2L, "FOOD_SAFETY_KOREA:11")).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(RecommendationExceptionCode.RECIPE_NOT_FOUND));
    }

    @Test
    void supportsLegacySnapshotsWithoutConfirmationCandidates() {
        add("recommendations", "1", "기존 추천");
        snapshot.remove("confirmationCandidates");
        store();

        assertThat(listService.get(1L, 2L, 10).recipes()).extracting(RecommendationListResult.RecipeSummary::name).containsExactly("기존 추천");
    }

    @Test
    void leavesBothEmptyListsEmpty() {
        store();

        assertThat(listService.get(1L, 2L, 10).recipes()).isEmpty();
    }

    @Test
    void usesTheSameConfirmationCandidateForDetailAndCooking() {
        add("confirmationCandidates", "2", "보충 요리");
        store();

        RecommendationDetailResult displayed = detailService.get(1L, 2L, "FOOD_SAFETY_KOREA:2");
        RecommendationDetailResult cooking = detailService.getForCooking(1L, 2L, 3L, "FOOD_SAFETY_KOREA:2");

        assertThat(displayed.name()).isEqualTo("보충 요리");
        assertThat(cooking).isEqualTo(displayed);
        verify(access).lockForWrite(1L, 2L);
        verify(ingredients).getByIdsForUpdate(eq(2L), anyList());
    }

    private ObjectNode add(String field, String id, String name) {
        ObjectNode added = recipe.deepCopy().put("sourceRecipeId", id).put("name", name);
        ((ArrayNode) snapshot.path(field)).add(added);
        return added;
    }

    private String store() {
        String storedSnapshot = snapshot.toString();
        selectedList = new RecommendationList(null, null, "request", storedSnapshot);
        ReflectionTestUtils.setField(selectedList, "id", 3L);
        state.completeGeneration(selectedList);
        state.selectLatestList();
        when(lists.findByIdAndUserIdAndRefrigeratorId(3L, 1L, 2L)).thenReturn(Optional.of(selectedList));
        return storedSnapshot;
    }
}
