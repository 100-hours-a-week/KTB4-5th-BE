package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.recipe.application.RecommendationDailyJobQueue.Lease;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyQuotaStore;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeSubmitResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class RecommendationFreshRequestTest extends ServiceIntegrationTest {
    @Autowired private RecommendationSubmissionService submissions;
    @Autowired private RecommendationCompletionService completions;
    @Autowired private RecommendationSelectionService selections;
    @Autowired private RecommendationOutboxService outbox;
    @Autowired private RecommendationStateRepository states;
    @Autowired private RecommendationDailyJobQueue queue;
    @Autowired private RecommendationScheduledSubmissionService scheduled;
    @Autowired private RecommendationDailyQuotaService quota;
    @Autowired private RecommendationDailyQuotaStore quotaStore;
    @Autowired private JsonMapper mapper;
    @Autowired private MockMvc mvc;
    @Autowired private JwtProvider jwt;
    @Autowired private RecommendationExecutionService execution;
    @MockitoBean private AiRecipeClient ai;

    @BeforeEach
    void prepareAiReceipt() {
        when(ai.submit(any())).thenAnswer(invocation -> {
            AiRecipeRecommendationRequest request = invocation.getArgument(0);
            return new AiRecipeSubmitResponse("job-" + request.requestId(), request.requestId(), AiRecipeJobResponse.Status.QUEUED,
                    OffsetDateTime.parse("2026-09-17T00:00:00Z"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameInputsEnqueueNewJobsEvenAfterEmptyResults(boolean emptyResult) throws Exception {
        Fixture participant = ownerFixture("새추천접수");
        RecommendationSubmissionResult first = submissions.submit(participant.userId(), participant.refrigeratorId(), null);
        execution.submit(first.eventId());
        complete(first, emptyResult);
        selections.selectLatest(participant.userId(), participant.refrigeratorId());
        Long latest = state(participant).getLatestList().getId();

        RecommendationSubmissionResult second = submissions.submit(participant.userId(), participant.refrigeratorId(), null);
        execution.submit(second.eventId());
        execution.submit(second.eventId());

        assertThat(second.recommendationId()).isNull();
        assertThat(second.eventId()).isNotNull().isNotEqualTo(first.eventId());
        assertThat(second.requestId()).isNotEqualTo(first.requestId());
        assertThat(second.dailyGeneration().remaining()).isEqualTo(8);
        assertThat(state(participant).getCurrentList().getId()).isEqualTo(latest);
        assertThat(state(participant).getLatestList().getId()).isEqualTo(latest);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);
        verify(ai).submit(outbox.request(first.eventId()));
        verify(ai).submit(outbox.request(second.eventId()));
        verifyNoMoreInteractions(ai);
    }

    @Test
    void returnsNewAcceptedJobAndKeepsProgressAndQuotaGuards() throws Exception {
        Fixture participant = ownerFixture("새요청HTTP");
        complete(submissions.submit(participant.userId(), participant.refrigeratorId(), null), true);
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie access = new Cookie("accessToken", jwt.createAccessToken(participant.userId(), UserRole.USER));
        String path = "/api/v1/refrigerators/" + participant.refrigeratorId() + "/recommendation-jobs";

        mvc.perform(post(path).cookie(csrf, access).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMEND-409-004"));
        selections.selectLatest(participant.userId(), participant.refrigeratorId());

        mvc.perform(post(path).cookie(csrf, access).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("RECOMMEND-202-001"))
                .andExpect(jsonPath("$.data.jobId").isString()).andExpect(jsonPath("$.data.dailyGeneration.remaining").value(8));
        mvc.perform(post(path).cookie(csrf, access).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMEND-409-003"));
        jdbcTemplate.update("UPDATE recommendation_states SET generation_status='FAILED', active_request_id=NULL WHERE user_id=?", participant.userId());
        for (int index = 0; index < 8; index++) { quotaStore.reserve(participant.userId(), BusinessTime.today(clock), "extra-" + index, 10, Duration.ofHours(12)); }
        mvc.perform(post(path).cookie(csrf, access).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RECOMMEND-429-001"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);
    }

    @Test
    void scheduledRequestEnqueuesNewJobInsteadOfReusingCompletedHistory() throws Exception {
        Fixture participant = ownerFixture("새자동추천");
        RecommendationSubmissionResult first = submissions.submit(participant.userId(), participant.refrigeratorId(), null);
        complete(first, true);
        queue.register(participant.userId(), participant.refrigeratorId(), BusinessTime.today(clock));
        Lease lease = queue.leaseBatch(BusinessTime.today(clock), 1, Duration.ofMinutes(2)).getFirst();

        scheduled.submit(lease);

        assertThat(state(participant).getActiveRequestId()).isEqualTo(lease.requestId()).isNotEqualTo(first.requestId());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM recommendation_daily_jobs", String.class)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);
        assertThat(quota.current(participant.userId()).remaining()).isEqualTo(9);
    }

    private RecommendationState state(Fixture participant) {
        return states.findByUserIdAndRefrigeratorId(participant.userId(), participant.refrigeratorId()).orElseThrow();
    }

    private void complete(RecommendationSubmissionResult submission, boolean empty) throws Exception {
        AiRecipeRecommendationRequest request = outbox.request(submission.eventId());
        ObjectNode response = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/recipe-ai/wiki-completed-response.json"));
        response.put("requestId", request.requestId()).put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId());
        ObjectNode result = (ObjectNode) response.get("result");
        result.put("userId", request.userId()).put("refrigeratorId", request.refrigeratorId())
                .put("inventoryFingerprint", request.inventoryFingerprint()).put("recommendationMode", request.effectiveMode().name());
        if (empty) {
            result.putArray("recommendations");
            result.putArray("confirmationCandidates");
        }
        completions.apply(mapper.treeToValue(response, AiRecipeJobResponse.class));
    }
}
