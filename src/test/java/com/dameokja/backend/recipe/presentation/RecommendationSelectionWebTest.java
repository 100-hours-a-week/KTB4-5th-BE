package com.dameokja.backend.recipe.presentation;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.application.RecommendationSubmissionService;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
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

@AutoConfigureMockMvc
class RecommendationSelectionWebTest extends ServiceIntegrationTest {
    private static final String PATH = "/api/v1/refrigerators/{refrigeratorId}/recommendations/current";
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private InvitationService invitationService;
    @Autowired private MembershipService membershipService;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    private Fixture refrigeratorParticipant;

    @BeforeEach
    void prepareParticipant() { refrigeratorParticipant = ownerFixture("전환회원"); }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectsLatestResultAndRepeatedRequestsKeepTheSameSelection(boolean hasPreviousSelection) throws Exception {
        Long latestListId = prepareCompletedResult(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), hasPreviousSelection);
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId()))
                .andExpect(status().isNoContent()).andExpect(content().string("")).andExpect(header().string("Cache-Control", "no-store"));
        assertSelected(latestListId);
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId())).andExpect(status().isNoContent());
        assertSelected(latestListId);
        assertThat(recommendationListRepository.count()).isEqualTo(hasPreviousSelection ? 2 : 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isZero();
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void missingHistoryReturnsNoContentWithoutCreatingState() throws Exception {
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(recommendationStateRepository.count()).isZero();
        assertThat(recommendationListRepository.count()).isZero();
        verifyNoInteractions(aiRecipeClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "FAILED"})
    void noCompletedResultKeepsExistingProgress(String generationStatus) throws Exception {
        RecommendationState recommendationState = recommendationStateRepository.saveAndFlush(new RecommendationState(
                userRepository.getReferenceById(refrigeratorParticipant.userId()), refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId())));
        String activeRequestId = generationStatus.equals("FAILED") ? null : UUID.randomUUID().toString();
        jdbcTemplate.update("UPDATE recommendation_states SET generation_status = ?, active_request_id = ? WHERE recommendation_state_id = ?",
                generationStatus, activeRequestId, recommendationState.getId());
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId())).andExpect(status().isNoContent());
        RecommendationState unchangedState = state();
        assertThat(unchangedState.getGenerationStatus().name()).isEqualTo(generationStatus);
        assertThat(unchangedState.getActiveRequestId()).isEqualTo(activeRequestId);
        assertThat(unchangedState.getCurrentList()).isNull();
    }

    @Test
    void changesOnlyTheAuthenticatedParticipantAndAllowsGenerationAfterSelection() throws Exception {
        Long latestListId = prepareCompletedResult(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), true);
        User otherParticipant = user("다른참여회원");
        join(otherParticipant, refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), true, false);
        prepareCompletedResult(otherParticipant.getId(), refrigeratorParticipant.refrigeratorId(), true);
        Long otherCurrentId = recommendationStateRepository.findByUserIdAndRefrigeratorId(otherParticipant.getId(), refrigeratorParticipant.refrigeratorId())
                .orElseThrow().getCurrentList().getId();
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId())).andExpect(status().isNoContent());
        assertSelected(latestListId);
        assertThat(recommendationStateRepository.findByUserIdAndRefrigeratorId(otherParticipant.getId(), refrigeratorParticipant.refrigeratorId()).orElseThrow()
                .getCurrentList().getId()).isEqualTo(otherCurrentId);
        assertThat(recommendationSubmissionService.submit(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), null).eventId()).isNotNull();
    }

    @Test
    void selectionDoesNotConsumeGenerationQuota() throws Exception {
        recommendationSubmissionService.submit(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), null);
        RecommendationList latestList = saveList(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId());
        jdbcTemplate.update("UPDATE recommendation_states SET latest_list_id = ?, generation_status = 'COMPLETED', active_request_id = NULL",
                latestList.getId());
        String quotaKey = "recipe:quota:" + refrigeratorParticipant.userId() + ":" + BusinessTime.today(clock);
        Long usedBefore = redisTemplate().opsForSet().size(quotaKey);
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId())).andExpect(status().isNoContent());
        assertSelected(latestList.getId());
        assertThat(redisTemplate().opsForSet().size(quotaKey)).isEqualTo(usedBefore).isEqualTo(1L);
    }

    @Test
    void changingTheCurrentRefrigeratorDeniesSelectionInThePreviousRefrigerator() throws Exception {
        Long latestListId = prepareCompletedResult(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), true);
        Long previousSelectionId = state().getCurrentList().getId();
        Fixture sharedOwner = ownerFixture("공유소유회원");
        membershipService.join(refrigeratorParticipant.userId(), invitationService.issueCode(sharedOwner.userId(), sharedOwner.refrigeratorId()).code());
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId())).andExpect(status().isForbidden());
        assertThat(state().getCurrentList().getId()).isEqualTo(previousSelectionId);
        membershipService.leave(refrigeratorParticipant.userId(), sharedOwner.refrigeratorId());
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId())).andExpect(status().isNoContent());
        assertSelected(latestListId);
    }

    @Test
    void enforcesAuthenticationCsrfAndRefrigeratorAccess() throws Exception {
        Cookie accessTokenCookie = new Cookie("accessToken", jwtProvider.createAccessToken(refrigeratorParticipant.userId(), UserRole.USER));
        mockMvc.perform(post(PATH, refrigeratorParticipant.refrigeratorId()).cookie(accessTokenCookie)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), null)).andExpect(status().isUnauthorized());
        Long otherRefrigeratorId = ownerFixture("외부회원").refrigeratorId();
        mockMvc.perform(request(otherRefrigeratorId, refrigeratorParticipant.userId())).andExpect(status().isForbidden());
        mockMvc.perform(request(Long.MAX_VALUE, refrigeratorParticipant.userId())).andExpect(status().isNotFound());
        mockMvc.perform(request("invalid", refrigeratorParticipant.userId())).andExpect(status().isBadRequest());
        jdbcTemplate.update("UPDATE refrigerators SET deleted_at = ? WHERE refrigerator_id = ?",
                BusinessTime.now(clock), refrigeratorParticipant.refrigeratorId());
        mockMvc.perform(request(refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId())).andExpect(status().isGone());
    }

    private Long prepareCompletedResult(Long userId, Long refrigeratorId, boolean hasPreviousSelection) {
        Long previousListId = hasPreviousSelection ? saveList(userId, refrigeratorId).getId() : null;
        RecommendationList latestList = saveList(userId, refrigeratorId);
        RecommendationState recommendationState = recommendationStateRepository.saveAndFlush(new RecommendationState(userRepository.getReferenceById(userId),
                refrigeratorRepository.getReferenceById(refrigeratorId)));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ?, generation_status = 'COMPLETED' WHERE recommendation_state_id = ?",
                previousListId, latestList.getId(), recommendationState.getId());
        return latestList.getId();
    }

    private RecommendationList saveList(Long userId, Long refrigeratorId) {
        return recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(userId),
                refrigeratorRepository.getReferenceById(refrigeratorId), UUID.randomUUID().toString(), "{}"));
    }

    private RecommendationState state() {
        return recommendationStateRepository.findByUserIdAndRefrigeratorId(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId()).orElseThrow();
    }

    private void assertSelected(Long latestListId) {
        RecommendationState recommendationState = state();
        assertThat(recommendationState.getCurrentList().getId()).isEqualTo(latestListId);
        assertThat(recommendationState.getLatestList().getId()).isEqualTo(latestListId);
        assertThat(recommendationState.getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.COMPLETED);
    }

    private MockHttpServletRequestBuilder request(Object refrigeratorId, Long userId) throws Exception {
        Cookie csrfTokenCookie = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MockHttpServletRequestBuilder selectionRequest = post(PATH, refrigeratorId).cookie(csrfTokenCookie).header("X-XSRF-TOKEN", csrfTokenCookie.getValue());
        return userId == null ? selectionRequest : selectionRequest.cookie(new Cookie("accessToken", jwtProvider.createAccessToken(userId, UserRole.USER)));
    }
}
