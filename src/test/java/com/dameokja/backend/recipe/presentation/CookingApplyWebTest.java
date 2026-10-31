package com.dameokja.backend.recipe.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class CookingApplyWebTest extends ServiceIntegrationTest {
    private static final String PATH = "/api/v1/refrigerators/{refrigeratorId}/cooking-records";
    private static final String RECIPE_ID = "FOOD_SAFETY_KOREA:1024";
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    private Fixture refrigeratorParticipant;
    private Long stockId;
    private RecommendationList selectedList;

    @BeforeEach
    void prepareSelectedRecipe() throws Exception {
        refrigeratorParticipant = ownerFixture("요리적용회원");
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, measure_type, weight_value, weight_unit, expiration_date, registration_source)
                VALUES (?, ?, '두부', 'TOFU_BEAN', 'WEIGHT', 400, 'G', ?, 'DIRECT')
                """, refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId(), BusinessTime.today(clock).plusDays(2));
        stockId = jdbcTemplate.queryForObject("SELECT ingredient_id FROM ingredients", Long.class);
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
        ((ObjectNode) snapshot.path("recommendations").get(0).path("inventoryUsage").get(0)).put("ingredientId", stockId);
        selectedList = recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), UUID.randomUUID().toString(), snapshot.toString()));
        RecommendationState recommendationState = recommendationStateRepository.saveAndFlush(new RecommendationState(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId())));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ?", selectedList.getId(), selectedList.getId());
    }

    @Test
    void returnsAggregatedDeductionsAndReplaysSuccessfulRequestsBeforeReadingTheSnapshot() throws Exception {
        shareStockBetweenRecipeIngredients();
        String confirmedEtag = detailEtag();
        mockMvc.perform(applyRequest("first", confirmedEtag)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RECOMMEND-200-004")).andExpect(jsonPath("$.message").value("추천 레시피 적용 성공"))
                .andExpect(jsonPath("$.data.recipeId").value(RECIPE_ID)).andExpect(jsonPath("$.data.useIngredients.length()").value(1))
                .andExpect(jsonPath("$.data.useIngredients[0].ingredientId").value(stockId.toString()))
                .andExpect(jsonPath("$.data.useIngredients[0].useAmount").value(300)).andExpect(jsonPath("$.data.useIngredients[0].unit").value("G"));
        assertThat(jdbcTemplate.queryForObject("SELECT weight_value FROM ingredients", BigDecimal.class)).isEqualByComparingTo("100");
        jdbcTemplate.update("DELETE FROM recommendation_states");
        recommendationListRepository.deleteAllInBatch();
        mockMvc.perform(applyRequest("first", confirmedEtag)).andExpect(status().isNoContent()).andExpect(content().string(""));
        mockMvc.perform(applyRequest("first", "\"" + "b".repeat(64) + "\"")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMEND-409-001"));
        assertOutcome(1);
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void changedStockRequiresReconfirmationBeforeAnyDeduction() throws Exception {
        String confirmedEtag = detailEtag();
        jdbcTemplate.update("UPDATE ingredients SET weight_value = 50 WHERE ingredient_id = ?", stockId);
        mockMvc.perform(applyRequest("old-detail", confirmedEtag)).andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RECOMMEND-412-001"))
                .andExpect(jsonPath("$.message").value("레시피 정보가 변경되었습니다. 다시 확인해 주세요."));
        assertOutcome(0);
        assertThat(jdbcTemplate.queryForObject("SELECT weight_value FROM ingredients", BigDecimal.class)).isEqualByComparingTo("50");
        mockMvc.perform(applyRequest("reconfirmed", detailEtag())).andExpect(status().isOk()).andExpect(jsonPath("$.data.useIngredients[0].useAmount").value(50));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isZero();
        assertOutcome(1);
    }

    @Test
    void recordsCookingWithoutReturningUnknownUsageAsADeduction() throws Exception {
        jdbcTemplate.update("UPDATE ingredients SET measure_type='COUNT', quantity=2, weight_value=NULL, weight_unit='NONE' WHERE ingredient_id=?", stockId);
        mockMvc.perform(applyRequest("unknown-usage", detailEtag())).andExpect(status().isOk()).andExpect(jsonPath("$.data.useIngredients").isEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM ingredients", Integer.class)).isEqualTo(2);
        assertOutcome(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingEtag", "unquotedEtag", "weakEtag", "wildcardEtag", "missingKey", "blankKey", "longKey", "overflowId", "badRecipe", "missingBodyFields"})
    void rejectsInvalidHeadersAndBodyWithoutSideEffects(String invalidRequest) throws Exception {
        String entityTag = detailEtag();
        String requestKey = "invalid-input";
        String requestBody = body(selectedList.getId().toString(), RECIPE_ID);
        switch (invalidRequest) {
            case "missingEtag" -> entityTag = null;
            case "unquotedEtag" -> entityTag = entityTag.replace("\"", "");
            case "weakEtag" -> entityTag = "W/" + entityTag;
            case "wildcardEtag" -> entityTag = "*";
            case "missingKey" -> requestKey = null;
            case "blankKey" -> requestKey = " ";
            case "longKey" -> requestKey = "x".repeat(65);
            case "overflowId" -> requestBody = body("9223372036854775808", RECIPE_ID);
            case "badRecipe" -> requestBody = body(selectedList.getId().toString(), "51");
            case "missingBodyFields" -> requestBody = "{}";
            default -> throw new IllegalArgumentException(invalidRequest);
        }
        MockHttpServletRequestBuilder request = authenticatedPost(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId()).content(requestBody);
        if (entityTag != null) { request.header("If-Match", entityTag); }
        if (requestKey != null) { request.header("Idempotency-Key", requestKey); }
        mockMvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        assertOutcome(0);
    }

    @Test
    void enforcesAuthenticationCsrfMembershipAndRecommendationOwnership() throws Exception {
        String confirmedEtag = detailEtag();
        mockMvc.perform(post(PATH, refrigeratorParticipant.refrigeratorId()).cookie(token(refrigeratorParticipant.userId()))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        mockMvc.perform(authenticatedPost(refrigeratorParticipant.refrigeratorId(), null).content(body(selectedList.getId().toString(), RECIPE_ID)))
                .andExpect(status().isUnauthorized());
        Fixture otherOwner = ownerFixture("외부조리회원");
        mockMvc.perform(authenticatedPost(otherOwner.refrigeratorId(), refrigeratorParticipant.userId()).header("If-Match", confirmedEtag)
                .header("Idempotency-Key", "other-fridge").content(body(selectedList.getId().toString(), RECIPE_ID))).andExpect(status().isForbidden());
        User otherParticipant = user("다른조리참여자");
        join(otherParticipant, refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), true, false);
        mockMvc.perform(authenticatedPost(refrigeratorParticipant.refrigeratorId(), otherParticipant.getId()).header("If-Match", confirmedEtag)
                .header("Idempotency-Key", "other-user").content(body(selectedList.getId().toString(), RECIPE_ID))).andExpect(status().isNotFound());
        assertOutcome(0);
    }

    @Test
    void allowsBrowserPreflightWithCookingHeaders() throws Exception {
        mockMvc.perform(options(PATH, refrigeratorParticipant.refrigeratorId()).header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "Content-Type,If-Match,Idempotency-Key,X-XSRF-TOKEN"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Headers", containsString("Idempotency-Key")));
    }

    private void shareStockBetweenRecipeIngredients() throws Exception {
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(selectedList.getResultJson());
        ObjectNode recipe = (ObjectNode) snapshot.path("recommendations").get(0);
        ArrayNode ingredients = (ArrayNode) recipe.path("ingredients");
        ingredients.add(((ObjectNode) ingredients.get(0)).deepCopy().put("ingredientNo", 2));
        ArrayNode requirements = (ArrayNode) recipe.path("ingredientRequirements");
        ((ObjectNode) requirements.get(0)).put("requiredAmount", new BigDecimal("150.125"));
        requirements.add(((ObjectNode) requirements.get(0)).deepCopy().put("ingredientNo", 2));
        ((ArrayNode) recipe.path("inventoryUsage").get(0).path("allocations")).addObject().put("ingredientNo", 2);
        jdbcTemplate.update("UPDATE recommendation_lists SET result_json = ? WHERE recommendation_list_id = ?", snapshot.toString(), selectedList.getId());
    }

    private MockHttpServletRequestBuilder applyRequest(String key, String entityTag) throws Exception {
        return authenticatedPost(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId()).header("Idempotency-Key", key)
                .header("If-Match", entityTag).content(body(selectedList.getId().toString(), RECIPE_ID));
    }

    private MockHttpServletRequestBuilder authenticatedPost(Long refrigeratorId, Long userId) throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MockHttpServletRequestBuilder request = post(PATH, refrigeratorId).cookie(csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue()).contentType("application/json");
        if (userId != null) { request.cookie(token(userId)); }
        return request;
    }

    private String detailEtag() throws Exception {
        return mockMvc.perform(get("/api/v1/refrigerators/{refrigeratorId}/recipes/{recipeId}", refrigeratorParticipant.refrigeratorId(), RECIPE_ID)
                .cookie(token(refrigeratorParticipant.userId()))).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private String body(String recommendationId, String recipeId) throws Exception { return jsonMapper.writeValueAsString(Map.of("recommendationId", recommendationId, "recipeId", recipeId)); }
    private Cookie token(Long userId) { return new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)); }
    private void assertOutcome(int count) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cooking_records", Integer.class)).isEqualTo(count);
        assertThat(userRepository.findById(refrigeratorParticipant.userId()).orElseThrow().getCookingCount()).isEqualTo(count);
    }
}
