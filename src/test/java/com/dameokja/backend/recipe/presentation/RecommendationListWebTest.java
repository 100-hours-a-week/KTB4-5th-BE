package com.dameokja.backend.recipe.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.nullValue;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.application.RecommendationSubmissionService;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyQuotaStore;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.user.domain.UserRole;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.support.ServiceIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.List;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

@AutoConfigureMockMvc
class RecommendationListWebTest extends ServiceIntegrationTest {
    private static final String PATH = "/api/v1/refrigerators/{refrigeratorId}/recommendations";
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private RecommendationDailyQuotaStore recommendationDailyQuotaStore;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    private Fixture refrigeratorParticipant;

    @BeforeEach
    void prepareParticipant() { refrigeratorParticipant = ownerFixture("목록회원"); }

    @Test
    void emptyHistoryIsReadOnlyAndReturnsNullSelection() throws Exception {
        mockMvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("RECOMMEND-200-001"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.status").value("EMPTY"))
                .andExpect(jsonPath("$.data.recommendationId").value(nullValue()))
                .andExpect(jsonPath("$.data.generatedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.hasNewRecommendation").value(false)).andExpect(jsonPath("$.data.recipes").isEmpty())
                .andExpect(jsonPath("$.data.dailyGeneration.remaining").value(10));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isZero();
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void newResultDoesNotReplaceSelectedResultUntilExplicitSelection() throws Exception {
        Long currentListId = prepareSelectedResult("이전요리");
        RecommendationList latestList = saveList("새요리");
        jdbcTemplate.update("UPDATE recommendation_states SET latest_list_id = ?", latestList.getId());
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = NULL");
        mockMvc.perform(request()).andExpect(jsonPath("$.data.status").value("EMPTY")).andExpect(jsonPath("$.data.hasNewRecommendation").value(true))
                .andExpect(jsonPath("$.data.recipes").isEmpty());
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?", currentListId);
        mockMvc.perform(request()).andExpect(jsonPath("$.data.recommendationId").value(currentListId.toString()))
                .andExpect(jsonPath("$.data.status").value("STALE"))
                .andExpect(jsonPath("$.data.hasNewRecommendation").value(true)).andExpect(jsonPath("$.data.recipes[0].name").value("이전요리"))
                .andExpect(jsonPath("$.data.recipes[0].recipeId").value("FOOD_SAFETY_KOREA:1024"))
                .andExpect(jsonPath("$.data.recipes[0].cookingTimeMinutes").value(nullValue()));
        Cookie csrfTokenCookie = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mockMvc.perform(post(PATH + "/current", refrigeratorParticipant.refrigeratorId()).cookie(accessTokenCookie(), csrfTokenCookie)
                .header("X-XSRF-TOKEN", csrfTokenCookie.getValue())).andExpect(status().isNoContent());
        mockMvc.perform(request()).andExpect(jsonPath("$.data.recommendationId").value(latestList.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.hasNewRecommendation").value(false)).andExpect(jsonPath("$.data.recipes[0].name").value("새요리"));
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void generationKeepsSelectedRecipesAndExposesTheBeJobId() throws Exception {
        Long currentListId = prepareSelectedResult("선택요리");
        String eventId = recommendationSubmissionService.submit(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), null).eventId();
        mockMvc.perform(request()).andExpect(jsonPath("$.data.status").value("GENERATING")).andExpect(jsonPath("$.data.activeJobId").value(eventId))
                .andExpect(jsonPath("$.data.generationTrigger").value("USER")).andExpect(jsonPath("$.data.generationStatus").value("QUEUED"))
                .andExpect(jsonPath("$.data.dailyGeneration.remaining").value(9))
                .andExpect(jsonPath("$.data.recommendationId").value(currentListId.toString())).andExpect(jsonPath("$.data.recipes[0].name").value("선택요리"));
    }

    @Test
    void inventoryChangesAndExhaustedQuotaDoNotChangeSelectedResultStatus() throws Exception {
        prepareSelectedResult("기존요리");
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, quantity, expiration_date, registration_source)
                VALUES (?, ?, '두부', 'TOFU_BEAN', 1, ?, 'DIRECT')
                """, refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId(), BusinessTime.today(clock).plusDays(2));
        mockMvc.perform(request()).andExpect(jsonPath("$.data.status").value("READY"));
        for (int reservationIndex = 0; reservationIndex < 10; reservationIndex++) {
            recommendationDailyQuotaStore.reserve(refrigeratorParticipant.userId(), BusinessTime.today(clock), "reserved-" + reservationIndex, 10, Duration.ofHours(1));
        }
        mockMvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.dailyGeneration.remaining").value(0))
                .andExpect(jsonPath("$.data.recipes[0].name").value("기존요리"));
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void validatesLimitAndAccess() throws Exception {
        mockMvc.perform(request().param("limit", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(request().param("limit", "11")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH, refrigeratorParticipant.refrigeratorId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH, ownerFixture("외부회원").refrigeratorId()).cookie(accessTokenCookie())).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH, Long.MAX_VALUE).cookie(accessTokenCookie())).andExpect(status().isNotFound());
    }

    @Test
    void preservesOrderAndLimitWhileIsolatingAnotherParticipant() throws Exception {
        prepareSelectedResult("첫요리");
        mockMvc.perform(request().param("limit", "1")).andExpect(jsonPath("$.data.recipes.length()").value(1))
                .andExpect(jsonPath("$.data.recipes[0].name").value("첫요리"));
        mockMvc.perform(request()).andExpect(jsonPath("$.data.recipes.length()").value(2)).andExpect(jsonPath("$.data.recipes[1].name").value("둘째요리"));
        User otherParticipant = user("다른참여회원");
        join(otherParticipant, refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), true, false);
        mockMvc.perform(get(PATH, refrigeratorParticipant.refrigeratorId()).cookie(new Cookie("accessToken", jwtProvider.createAccessToken(otherParticipant.getId(), UserRole.USER))))
                .andExpect(jsonPath("$.data.status").value("EMPTY")).andExpect(jsonPath("$.data.recipes").isEmpty());
    }

    @Test
    void countsMappedIngredientNumbersFromLiveInventoryAndKeepsUnknownCountsNull() throws Exception {
        Long selectedListId = prepareSelectedResult("두부요리");
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, quantity, expiration_date, registration_source)
                VALUES (?, ?, '국산 두부', 'TOFU_BEAN', 1, ?, 'DIRECT'), (?, ?, '큰 두부', 'TOFU_BEAN', 1, ?, 'DIRECT')
                """, refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId(), BusinessTime.today(clock).plusDays(2),
                refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId(), BusinessTime.today(clock).plusDays(7));
        List<Long> mappedStockIds = jdbcTemplate.queryForList("SELECT ingredient_id FROM ingredients ORDER BY ingredient_id", Long.class);
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(recommendationListRepository.findById(selectedListId).orElseThrow().getResultJson());
        ObjectNode inventoryUsage = (ObjectNode) snapshot.path("recommendations").get(0).path("inventoryUsage").get(0);
        inventoryUsage.put("ingredientId", mappedStockIds.get(0));
        ((ArrayNode) snapshot.path("recommendations").get(0).path("inventoryUsage")).add(inventoryUsage.deepCopy().put("ingredientId", mappedStockIds.get(1)));
        jdbcTemplate.update("UPDATE recommendation_lists SET result_json = ? WHERE recommendation_list_id = ?", snapshot.toString(), selectedListId);
        mockMvc.perform(request()).andExpect(jsonPath("$.data.recipes[0].ingredientCounts.total").value(1))
                .andExpect(jsonPath("$.data.recipes[0].ingredientCounts.owned").value(1)).andExpect(jsonPath("$.data.recipes[0].ingredientCounts.expiring").value(1));
        jdbcTemplate.update("DELETE FROM ingredients WHERE ingredient_id = ?", mappedStockIds.get(0));
        mockMvc.perform(request()).andExpect(jsonPath("$.data.recipes[0].ingredientCounts.owned").value(1))
                .andExpect(jsonPath("$.data.recipes[0].ingredientCounts.expiring").value(0));
        jdbcTemplate.update("DELETE FROM ingredients");
        mockMvc.perform(request()).andExpect(jsonPath("$.data.recipes[0].ingredientCounts.owned").value(0));
        inventoryUsage.put("allocationStatus", "UNRESOLVED").putArray("allocations");
        jdbcTemplate.update("UPDATE recommendation_lists SET result_json = ? WHERE recommendation_list_id = ?", snapshot.toString(), selectedListId);
        mockMvc.perform(request()).andExpect(jsonPath("$.data.recipes[0].ingredientCounts.owned").value(nullValue()))
                .andExpect(jsonPath("$.data.recipes[0].ingredientCounts.expiring").value(nullValue()));
    }

    private Long prepareSelectedResult(String recipeName) throws Exception {
        RecommendationList selectedList = saveList(recipeName);
        RecommendationState recommendationState = recommendationStateRepository.saveAndFlush(new RecommendationState(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId())));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ?, generation_status = 'COMPLETED' WHERE recommendation_state_id = ?",
                selectedList.getId(), selectedList.getId(), recommendationState.getId());
        return selectedList.getId();
    }

    private RecommendationList saveList(String recipeName) throws Exception {
        ObjectNode resultBody = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
        resultBody.put("userId", refrigeratorParticipant.userId()).put("refrigeratorId", refrigeratorParticipant.refrigeratorId());
        ((ObjectNode) resultBody.path("recommendations").get(0)).put("name", recipeName);
        ObjectNode secondRecipe = ((ObjectNode) resultBody.path("recommendations").get(0)).deepCopy().put("name", "둘째요리").put("sourceRecipeId", "2048");
        ((ArrayNode) resultBody.path("recommendations")).add(secondRecipe);
        return recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), UUID.randomUUID().toString(), resultBody.toString()));
    }

    private MockHttpServletRequestBuilder request() { return get(PATH, refrigeratorParticipant.refrigeratorId()).cookie(accessTokenCookie()); }

    private Cookie accessTokenCookie() { return new Cookie("accessToken", jwtProvider.createAccessToken(refrigeratorParticipant.userId(), UserRole.USER)); }
}
