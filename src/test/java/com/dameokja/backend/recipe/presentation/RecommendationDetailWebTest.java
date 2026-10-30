package com.dameokja.backend.recipe.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.application.RecommendationSelectionService;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class RecommendationDetailWebTest extends ServiceIntegrationTest {
    private static final String PATH = "/api/v1/refrigerators/{refrigeratorId}/recipes/{recipeId}";
    private static final String RECIPE_ID = "FOOD_SAFETY_KOREA:1024";
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private RecommendationSelectionService recommendationSelectionService;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    private Fixture participant;
    private Long stockId;

    @BeforeEach
    void prepareStock() {
        participant = ownerFixture("상세회원");
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, measure_type, quantity, weight_value, weight_unit, expiration_date, registration_source)
                VALUES (?, ?, '국산 두부', 'TOFU_BEAN', 'WEIGHT', NULL, 400, 'G', ?, 'DIRECT')
                """, participant.refrigeratorId(), participant.userId(), BusinessTime.today(clock).plusDays(2));
        stockId = jdbcTemplate.queryForObject("SELECT ingredient_id FROM ingredients", Long.class);
    }

    @Test
    void exposesDetailAndCurrentAllocationWithoutDeductingStock() throws Exception {
        prepareSelection();
        MvcResult initialResponse = mockMvc.perform(request(RECIPE_ID)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RECOMMEND-200-002")).andExpect(jsonPath("$.data.recipeId").value(RECIPE_ID))
                .andExpect(jsonPath("$.data.targetServings").value(2)).andExpect(jsonPath("$.data.cookingTimeMinutes").value(nullValue()))
                .andExpect(jsonPath("$.data.expiringIngredientCount").value(1)).andExpect(jsonPath("$.data.ingredients[0].recipeIngredientId").value("1"))
                .andExpect(jsonPath("$.data.ingredients[0].requiredAmount").value(300)).andExpect(jsonPath("$.data.ingredients[0].unit").value("G"))
                .andExpect(jsonPath("$.data.ingredients[0].stocks[0].ingredientId").value(stockId.toString()))
                .andExpect(jsonPath("$.data.ingredients[0].stocks[0].ownedAmount").value(400)).andExpect(jsonPath("$.data.ingredients[0].stocks[0].usedAmount").value(300))
                .andExpect(jsonPath("$.data.steps[0].instruction").value("두부를 조립니다.")).andReturn();
        String initialEntityTag = initialResponse.getResponse().getHeader("ETag");
        assertThat(initialEntityTag).startsWith("\"").endsWith("\"");
        jdbcTemplate.update("UPDATE ingredients SET updated_at = ? WHERE ingredient_id = ?", BusinessTime.now(clock).plusSeconds(1), stockId);
        mockMvc.perform(request(RECIPE_ID).header("If-None-Match", initialEntityTag)).andExpect(status().isNotModified()).andExpect(header().string("ETag", initialEntityTag));
        jdbcTemplate.update("UPDATE ingredients SET weight_value = 50 WHERE ingredient_id = ?", stockId);
        mockMvc.perform(request(RECIPE_ID)).andExpect(header().string("ETag", not(initialEntityTag))).andExpect(jsonPath("$.data.ingredients[0].stocks[0].ownedAmount").value(50))
                .andExpect(jsonPath("$.data.ingredients[0].stocks[0].usedAmount").value(50));
        assertThat(jdbcTemplate.queryForObject("SELECT weight_value FROM ingredients WHERE ingredient_id = ?", BigDecimal.class, stockId)).isEqualByComparingTo("50");
        jdbcTemplate.update("DELETE FROM ingredients WHERE ingredient_id = ?", stockId);
        mockMvc.perform(request(RECIPE_ID)).andExpect(jsonPath("$.data.ingredients[0].stocks").isEmpty());
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void continuesUsingSelectedSnapshotUntilExplicitSelection() throws Exception {
        prepareSelection();
        RecommendationList latest = saveList("새 요리");
        jdbcTemplate.update("UPDATE recommendation_states SET latest_list_id = ?", latest.getId());
        mockMvc.perform(request(RECIPE_ID)).andExpect(jsonPath("$.data.name").value("이전 요리"));
        recommendationSelectionService.selectLatest(participant.userId(), participant.refrigeratorId());
        mockMvc.perform(request(RECIPE_ID)).andExpect(jsonPath("$.data.name").value("새 요리"));
    }

    @Test
    void returnsNotFoundForNoSelectionOrMissingRecipeAndIsolatesParticipants() throws Exception {
        mockMvc.perform(request(RECIPE_ID)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMEND-404-001"));
        prepareSelection();
        mockMvc.perform(request("FOOD_SAFETY_KOREA:9999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMEND-404-001"));
        User otherParticipant = user("다른상세회원");
        join(otherParticipant, refrigeratorRepository.getReferenceById(participant.refrigeratorId()), true, false);
        mockMvc.perform(get(PATH, participant.refrigeratorId(), RECIPE_ID).cookie(token(otherParticipant.getId())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMEND-404-001"));
    }

    @Test
    void retainsExpirationAndUnknownUsageWhenStockMeasurementChanged() throws Exception {
        prepareSelection();
        jdbcTemplate.update("UPDATE ingredients SET measure_type = 'COUNT', quantity = 2, weight_value = NULL, weight_unit = 'NONE' WHERE ingredient_id = ?", stockId);
        mockMvc.perform(request(RECIPE_ID)).andExpect(jsonPath("$.data.ingredients[0].stocks[0].ownedAmount").value(2))
                .andExpect(jsonPath("$.data.ingredients[0].stocks[0].unit").value("EA"))
                .andExpect(jsonPath("$.data.ingredients[0].stocks[0].usedAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.ingredients[0].stocks[0].allocationStatus").value("UNRESOLVED"))
                .andExpect(jsonPath("$.data.ingredients[0].stocks[0].expirationDate").value(BusinessTime.today(clock).plusDays(2).toString()));
    }

    @Test
    void usesTargetRequirementsAndPreservesIngredientAndStepOrder() throws Exception {
        RecommendationList selected = prepareSelection();
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(selected.getResultJson());
        ObjectNode recipe = (ObjectNode) snapshot.path("recommendations").get(0);
        recipe.put("targetServings", 4);
        ((ObjectNode) recipe.path("ingredientRequirements").get(0)).put("requiredAmount", 600);
        ObjectNode originalIngredient = ((ObjectNode) recipe.path("ingredients").get(0)).deepCopy();
        ObjectNode unknownIngredient = originalIngredient.deepCopy().put("ingredientNo", 7).put("name", "참기름").put("amountText", "1큰술").putNull("unit");
        recipe.putArray("ingredients").add(unknownIngredient).add(originalIngredient);
        ArrayNode steps = (ArrayNode) recipe.path("steps");
        ((ObjectNode) steps.get(0)).put("stepNo", 10);
        steps.addObject().put("stepNo", 2).put("instruction", "순서를 유지합니다.");
        jdbcTemplate.update("UPDATE recommendation_lists SET result_json = ? WHERE recommendation_list_id = ?", snapshot.toString(), selected.getId());
        mockMvc.perform(request(RECIPE_ID)).andExpect(jsonPath("$.data.targetServings").value(4))
                .andExpect(jsonPath("$.data.ingredients[0].recipeIngredientId").value("7"))
                .andExpect(jsonPath("$.data.ingredients[0].requiredAmount").value(nullValue())).andExpect(jsonPath("$.data.ingredients[0].unit").value(nullValue()))
                .andExpect(jsonPath("$.data.ingredients[1].recipeIngredientId").value("1")).andExpect(jsonPath("$.data.ingredients[1].requiredAmount").value(600))
                .andExpect(jsonPath("$.data.steps[0].stepNo").value(10)).andExpect(jsonPath("$.data.steps[1].stepNo").value(2));
    }

    @Test
    void checksAccessEvenWithoutSelectedResult() throws Exception {
        mockMvc.perform(get(PATH, participant.refrigeratorId(), RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH, ownerFixture("외부회원").refrigeratorId(), RECIPE_ID).cookie(token(participant.userId()))).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH, Long.MAX_VALUE, RECIPE_ID).cookie(token(participant.userId()))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFRIGERATOR-404-001"));
        mockMvc.perform(request("51")).andExpect(status().isBadRequest());
        jdbcTemplate.update("UPDATE refrigerators SET deleted_at = ? WHERE refrigerator_id = ?", BusinessTime.now(clock), participant.refrigeratorId());
        mockMvc.perform(request(RECIPE_ID)).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("REFRIGERATOR-410-001"));
    }

    private RecommendationList prepareSelection() throws Exception {
        RecommendationList selected = saveList("이전 요리");
        RecommendationState state = recommendationStateRepository.saveAndFlush(new RecommendationState(userRepository.getReferenceById(participant.userId()),
                refrigeratorRepository.getReferenceById(participant.refrigeratorId())));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ? WHERE recommendation_state_id = ?", selected.getId(), selected.getId(), state.getId());
        return selected;
    }

    private RecommendationList saveList(String name) throws Exception {
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
        snapshot.put("userId", participant.userId()).put("refrigeratorId", participant.refrigeratorId());
        ObjectNode recipe = (ObjectNode) snapshot.path("recommendations").get(0);
        recipe.put("name", name);
        ((ObjectNode) recipe.path("inventoryUsage").get(0)).put("ingredientId", stockId);
        return recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(participant.userId()),
                refrigeratorRepository.getReferenceById(participant.refrigeratorId()), UUID.randomUUID().toString(), snapshot.toString()));
    }

    private MockHttpServletRequestBuilder request(String recipeId) { return get(PATH, participant.refrigeratorId(), recipeId).cookie(token(participant.userId())); }
    private Cookie token(Long userId) { return new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)); }
}
