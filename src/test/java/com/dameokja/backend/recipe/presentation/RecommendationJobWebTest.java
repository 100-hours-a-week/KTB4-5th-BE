package com.dameokja.backend.recipe.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyQuotaStore;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class RecommendationJobWebTest extends ServiceIntegrationTest {
    private static final String PATH = "/api/v1/refrigerators/{refrigeratorId}/recommendation-jobs";
    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @Autowired JsonMapper jsonMapper;
    @Autowired RecommendationListRepository recommendationListRepository;
    @Autowired RecommendationDailyQuotaStore recommendationDailyQuotaStore;
    @MockitoBean AiRecipeClient aiRecipeClient;
    private Fixture refrigeratorParticipant;

    @BeforeEach
    void prepareParticipant() { refrigeratorParticipant = ownerFixture("API회원"); }

    @Test
    void acceptsDefaultsAndBlocksDuplicateGeneration() throws Exception {
        mockMvc.perform(request("{}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("RECOMMEND-202-001"))
                .andExpect(jsonPath("$.data.jobId").isString()).andExpect(jsonPath("$.data.mode").value("EXCLUDE_EXPIRED"))
                .andExpect(jsonPath("$.data.status").value("QUEUED")).andExpect(jsonPath("$.data.pollAfterMs").value(1000))
                .andExpect(jsonPath("$.data.dailyGeneration.remaining").value(9));
        mockMvc.perform(request("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMEND-409-003"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(1);
        JsonNode storedRecommendationRequest = jsonMapper.readTree(jdbcTemplate.queryForObject("SELECT payload FROM recommendation_outbox", String.class));
        assertThat(storedRecommendationRequest.path("userId").asLong()).isEqualTo(refrigeratorParticipant.userId());
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void forwardsEverySelectedOption() throws Exception {
        String requestBody = """
                {"mode":"INCLUDE_EXPIRED","preferenceTags":["한식"],"limit":3,"targetServings":4,
                 "strategies":["SIMPLE_COOKING"],"excludedIngredientNames":["우유"],
                 "pantryIngredientNames":["소금"],"recentRecipeIds":["RDA:123"]}
                """;
        mockMvc.perform(request(requestBody)).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.mode").value("INCLUDE_EXPIRED"));
        JsonNode storedRecommendationRequest = jsonMapper.readTree(jdbcTemplate.queryForObject("SELECT payload FROM recommendation_outbox", String.class));
        JsonNode expectedRequestFields = jsonMapper.readTree(requestBody);
        assertThat(storedRecommendationRequest.get("recommendationMode")).isEqualTo(expectedRequestFields.get("mode"));
        for (String optionFieldName : new String[]{"preferenceTags", "limit", "targetServings", "strategies", "excludedIngredientNames", "pantryIngredientNames", "recentRecipeIds"}) {
            assertThat(storedRecommendationRequest.get(optionFieldName)).isEqualTo(expectedRequestFields.get(optionFieldName));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "{\"mode\":\"INVALID\"}", "{\"limit\":0}", "{\"limit\":11}", "{\"targetServings\":13}", "{\"strategies\":[]}",
            "{\"strategies\":[\"USE_EXPIRING\",\"USE_EXPIRING\"]}", "{\"strategies\":[null]}", "{\"preferenceTags\":[\" \"]}",
            "{\"pantryIngredientNames\":[\"\"]}", "{\"recentRecipeIds\":[\"123\"]}"})
    void rejectsInvalidInputsWithoutAcceptingAnEvent(String requestBody) throws Exception {
        mockMvc.perform(request(requestBody)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"mode\":\"EXCLUDE_EXPIRED\",\"limit\":10,\"targetServings\":2,\"preferenceTags\":[],\"excludedIngredientNames\":[]}"})
    void reusesMatchingSelectedResultWithoutConsumingQuota(String requestBody) throws Exception {
        mockMvc.perform(request("{}")).andExpect(status().isAccepted());
        Long recommendationListId = finishResult(true);
        mockMvc.perform(request(requestBody)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("RECOMMEND-200-003"))
                .andExpect(jsonPath("$.data.status").value("ALREADY_UP_TO_DATE")).andExpect(jsonPath("$.data.recommendationId").value(recommendationListId.toString()))
                .andExpect(jsonPath("$.data.jobId").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.data.dailyGeneration.remaining").value(9));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"mode\":\"INCLUDE_EXPIRED\"}", "{\"targetServings\":4}"})
    void generatesAgainWhenSelectedOptionsChange(String requestBody) throws Exception {
        mockMvc.perform(request("{}")).andExpect(status().isAccepted());
        finishResult(true);
        mockMvc.perform(request(requestBody)).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.dailyGeneration.remaining").value(8));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);
    }

    @Test
    void generatesAgainWhenRefrigeratorInventoryChanges() throws Exception {
        mockMvc.perform(request("{}")).andExpect(status().isAccepted());
        finishResult(true);
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, quantity, expiration_date, registration_source)
                VALUES (?, ?, '두부', 'TOFU_BEAN', 1, ?, 'DIRECT')
                """, refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId(), BusinessTime.today(clock).plusDays(7));
        mockMvc.perform(request("{}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.dailyGeneration.remaining").value(8));
    }

    @Test
    void blocksAnUnselectedCompletedResult() throws Exception {
        mockMvc.perform(request("{}")).andExpect(status().isAccepted());
        finishResult(false);
        mockMvc.perform(request("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMEND-409-004"));
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 41})
    void validatesTagLengthByUnicodeCodePoints(int tagCodePointLength) throws Exception {
        mockMvc.perform(request("{\"preferenceTags\":[\"" + "😀".repeat(tagCodePointLength) + "\"]}"))
                .andExpect(status().is(tagCodePointLength == 40 ? 202 : 400));
    }

    @Test
    void enforcesAuthenticationCsrfAndRefrigeratorAccess() throws Exception {
        Cookie accessTokenCookie = new Cookie("accessToken", jwtProvider.createAccessToken(refrigeratorParticipant.userId(), UserRole.USER));
        mockMvc.perform(post(PATH, refrigeratorParticipant.refrigeratorId()).cookie(accessTokenCookie).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        mockMvc.perform(request("{}", refrigeratorParticipant.refrigeratorId(), null)).andExpect(status().isUnauthorized());
        Long otherRefrigeratorId = ownerFixture("외부회원").refrigeratorId();
        mockMvc.perform(request("{}", otherRefrigeratorId, refrigeratorParticipant.userId())).andExpect(status().isForbidden());
    }

    @Test
    void returnsTheDailyQuotaErrorWithoutCreatingAnEvent() throws Exception {
        for (int reservationIndex = 0; reservationIndex < 10; reservationIndex++) {
            recommendationDailyQuotaStore.reserve(refrigeratorParticipant.userId(), BusinessTime.today(clock), "quota-" + reservationIndex, 10, Duration.ofHours(12));
        }
        mockMvc.perform(request("{}")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RECOMMEND-429-001"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isZero();
    }

    @Test
    void preservesExistingRefrigeratorMissingAndDeletedErrors() throws Exception {
        mockMvc.perform(request("{}", Long.MAX_VALUE, refrigeratorParticipant.userId())).andExpect(status().isNotFound());
        jdbcTemplate.update("UPDATE refrigerators SET deleted_at = ? WHERE refrigerator_id = ?", BusinessTime.now(clock), refrigeratorParticipant.refrigeratorId());
        mockMvc.perform(request("{}")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("REFRIGERATOR-410-001"));
    }

    private Long finishResult(boolean selectCompletedResult) {
        String recommendationRequestId = jdbcTemplate.queryForObject("SELECT request_id FROM recommendation_outbox", String.class);
        Long recommendationListId = recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), recommendationRequestId, "{}")).getId();
        jdbcTemplate.update("""
                UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ?, generation_status = 'COMPLETED', active_request_id = NULL
                WHERE user_id = ? AND refrigerator_id = ?
                """, selectCompletedResult ? recommendationListId : null, recommendationListId, refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId());
        return recommendationListId;
    }

    private MockHttpServletRequestBuilder request(String requestBody) throws Exception {
        return request(requestBody, refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId());
    }

    private MockHttpServletRequestBuilder request(String requestBody, Long refrigeratorId, Long userId) throws Exception {
        Cookie csrfTokenCookie = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MockHttpServletRequestBuilder recommendationRequestBuilder = post(PATH, refrigeratorId).cookie(csrfTokenCookie)
                .header("X-XSRF-TOKEN", csrfTokenCookie.getValue()).contentType("application/json").content(requestBody);
        return userId == null ? recommendationRequestBuilder : recommendationRequestBuilder.cookie(new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)));
    }
}
