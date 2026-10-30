package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.RecommendationJobStore;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeSubmitResponse;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class RecommendationWorkerCallbackTest extends ServiceIntegrationTest {
    private static final String PATH = "/api/v1/internal/recipe-recommendations/callback";
    @Autowired private RecommendationWorker recommendationWorker;
    @Autowired private RecommendationOutboxService recommendationOutboxService;
    @Autowired private com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository recommendationOutboxRepository;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    @MockitoSpyBean private RecommendationJobStore recommendationJobStore;
    private RecommendationSubmissionResult submissionResult;
    private AiRecipeJobResponse completedResponse;
    private AiRecipeSubmitResponse submitResponse;
    private MockMvc mockMvc;

    @BeforeEach
    void prepare() throws Exception {
        Fixture refrigeratorOwner = ownerFixture("추천콜백회원");
        AiRecipeRecommendationRequest recommendationRequest = RecommendationOptions.empty().toRequest("request", refrigeratorOwner.refrigeratorId(), List.of());
        submissionResult = recommendationOutboxService.enqueue(recommendationRequest);
        ObjectNode callbackBody = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/wiki-completed-response.json"));
        callbackBody.put("jobId", "ai-job").put("requestId", recommendationRequest.requestId()).put("refrigeratorId", refrigeratorOwner.refrigeratorId());
        ((ObjectNode) callbackBody.get("result")).put("refrigeratorId", refrigeratorOwner.refrigeratorId()).put("inventoryFingerprint", recommendationRequest.inventoryFingerprint());
        completedResponse = jsonMapper.treeToValue(callbackBody, AiRecipeJobResponse.class);
        submitResponse = new AiRecipeSubmitResponse("ai-job", recommendationRequest.requestId(), AiRecipeJobResponse.Status.QUEUED, completedResponse.submittedAt());
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanOutbox() {
        await().atMost(Duration.ofSeconds(5)).until(recommendationWorker::isIdle);
        recommendationOutboxRepository.deleteAll();
    }

    @Test
    void workerSubmitsAndQueriesOutsideDatabaseTransactions() {
        when(aiRecipeClient.submit(any())).thenReturn(submitResponse);
        when(aiRecipeClient.get(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return completedResponse;
        });
        recommendationWorker.poll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recommendationJobStore.find(submissionResult.eventId()).orElseThrow().view()).isEqualTo(completedResponse));
        verify(aiRecipeClient).submit(recommendationOutboxService.request(submissionResult.eventId()));
        verify(aiRecipeClient).get("ai-job", recommendationOutboxService.request(submissionResult.eventId()));
    }

    @Test
    void repeatedPollingDoesNotStartTheSameLocalTaskTwice() throws Exception {
        CountDownLatch requestStartedLatch = new CountDownLatch(1);
        CountDownLatch allowSubmissionLatch = new CountDownLatch(1);
        when(aiRecipeClient.submit(any())).thenAnswer(invocation -> {
            requestStartedLatch.countDown();
            assertThat(allowSubmissionLatch.await(5, TimeUnit.SECONDS)).isTrue();
            return submitResponse;
        });
        when(aiRecipeClient.get(any(), any())).thenReturn(completedResponse);
        try {
            recommendationWorker.poll();
            assertThat(requestStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            recommendationWorker.poll();
            recommendationWorker.poll();
            verify(aiRecipeClient).submit(any());
        } finally {
            allowSubmissionLatch.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recommendationJobStore.find(submissionResult.eventId()).orElseThrow().view()).isEqualTo(completedResponse));
    }

    @Test
    void authenticatesAndAppliesDuplicateCallbacksWithoutExtendingRetention() throws Exception {
        recommendationJobStore.accept(submissionResult.eventId(), submitResponse);
        assertThat(send(completedResponse, null)).isEqualTo(401);
        assertThat(send(completedResponse, "wrong")).isEqualTo(401);
        assertThat(send(completedResponse, "test-key")).isEqualTo(204);
        String recipeJobKey = "recipe:job:" + submissionResult.eventId();
        redisTemplate().expire(recipeJobKey, Duration.ofMinutes(10));
        assertThat(send(completedResponse, "test-key")).isEqualTo(204);
        assertThat(recommendationJobStore.find(submissionResult.eventId()).orElseThrow().view()).isEqualTo(completedResponse);
        assertThat(redisTemplate().getExpire(recipeJobKey)).isBetween(590L, 600L);
    }

    @Test
    void retriesAnEarlyCallbackAndStorageFailureAndRejectsUnrelatedPayload() throws Exception {
        assertThat(send(completedResponse, "test-key")).isEqualTo(503);
        recommendationJobStore.accept(submissionResult.eventId(), submitResponse);
        doThrow(new DataAccessResourceFailureException("redis")).doCallRealMethod().when(recommendationJobStore).update(any(), any());
        assertThat(send(completedResponse, "test-key")).isEqualTo(503);
        assertThat(send(completedResponse, "test-key")).isEqualTo(204);
        ObjectNode invalidCallbackBody = jsonMapper.valueToTree(completedResponse);
        invalidCallbackBody.put("refrigeratorId", completedResponse.refrigeratorId() + 1);
        assertThat(send(invalidCallbackBody, "test-key")).isEqualTo(400);
        assertThat(recommendationJobStore.find(submissionResult.eventId()).orElseThrow().view()).isEqualTo(completedResponse);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jobId", "requestId", "refrigeratorId", "inventoryFingerprint", "status"})
    void rejectsMismatchedAndNonterminalCallbacks(String fieldName) throws Exception {
        recommendationJobStore.accept(submissionResult.eventId(), submitResponse);
        ObjectNode invalidCallbackBody = jsonMapper.valueToTree(completedResponse);
        if (fieldName.equals("refrigeratorId")) {
            invalidCallbackBody.put(fieldName, completedResponse.refrigeratorId() + 1);
        } else {
            ObjectNode targetCallbackBody = fieldName.equals("inventoryFingerprint") ? (ObjectNode) invalidCallbackBody.get("result") : invalidCallbackBody;
            targetCallbackBody.put(fieldName, fieldName.equals("status") ? "PROCESSING" : "unrelated");
        }
        assertThat(send(invalidCallbackBody, "test-key")).isEqualTo(fieldName.equals("requestId") ? 404 : 400);
        assertThat(recommendationJobStore.find(submissionResult.eventId()).orElseThrow().view()).isNull();
    }

    @Test
    void sharesCompletionAcrossRepeatedRequestsAndDoesNotRecreateExpiredResults() throws Exception {
        RecommendationSubmissionResult secondSubmissionResult = recommendationOutboxService.enqueue(recommendationOutboxService.request(submissionResult.eventId()));
        recommendationJobStore.accept(submissionResult.eventId(), submitResponse);
        recommendationJobStore.accept(secondSubmissionResult.eventId(), submitResponse);
        assertThat(send(completedResponse, "test-key")).isEqualTo(204);
        assertThat(recommendationJobStore.find(secondSubmissionResult.eventId()).orElseThrow().view()).isEqualTo(completedResponse);
        recommendationOutboxService.markSent(submissionResult.eventId());
        recommendationOutboxService.markSent(secondSubmissionResult.eventId());
        redisTemplate().delete(List.of("recipe:job:" + submissionResult.eventId(), "recipe:job:" + secondSubmissionResult.eventId()));
        assertThat(send(completedResponse, "test-key")).isEqualTo(204);
        assertThat(recommendationJobStore.find(submissionResult.eventId())).isEmpty();
        assertThat(recommendationJobStore.find(secondSubmissionResult.eventId())).isEmpty();
    }

    @Test
    void retriesAReadFailureWithoutSubmittingTheAcceptedRequestAgain() {
        when(aiRecipeClient.submit(any())).thenReturn(submitResponse);
        when(aiRecipeClient.get(any(), any())).thenThrow(new org.springframework.web.client.RestClientException("GET unavailable")).thenReturn(completedResponse);
        recommendationWorker.poll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            recommendationWorker.poll();
            assertThat(recommendationJobStore.find(submissionResult.eventId()).orElseThrow().view()).isEqualTo(completedResponse);
        });
        verify(aiRecipeClient).submit(recommendationOutboxService.request(submissionResult.eventId()));
        verify(aiRecipeClient, times(2)).get("ai-job", recommendationOutboxService.request(submissionResult.eventId()));
    }

    private int send(Object callbackPayload, String internalApiKey) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callbackHttpRequest = post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(callbackPayload));
        if (internalApiKey != null) { callbackHttpRequest.header("X-Internal-API-Key", internalApiKey); }
        return mockMvc.perform(callbackHttpRequest).andReturn().getResponse().getStatus();
    }
}
