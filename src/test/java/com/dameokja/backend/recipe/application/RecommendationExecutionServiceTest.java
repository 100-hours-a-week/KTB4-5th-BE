package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.RecommendationJobStore;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse.Status;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeSubmitResponse;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecommendationExecutionServiceTest extends ServiceIntegrationTest {
    @Autowired private RecommendationExecutionService execution;
    @MockitoSpyBean private RecommendationOutboxService outbox;
    @Autowired private RecommendationOutboxRepository repository;
    @Autowired private JsonMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private AiRecipeClient ai;
    @MockitoSpyBean private RecommendationJobStore jobs;
    private static final OffsetDateTime SUBMITTED = OffsetDateTime.parse("2026-10-29T03:00:00Z");

    @AfterEach
    void cleanOutbox() { repository.deleteAll(); }

    @Test
    void submitsCommittedSnapshotOutsideTransactionAndReusesReceipt() {
        RecommendationSubmissionResult event = event();
        AiRecipeRecommendationRequest frozen = outbox.request(event.eventId());
        when(ai.submit(frozen)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(repository.findByEventId(event.eventId())).isPresent();
            return receipt(event);
        });
        assertThat(execution.submit(event.eventId()).acceptance()).isEqualTo(receipt(event));
        assertThat(repository.findByEventId(event.eventId()).orElseThrow().getStatus()).isEqualTo(RecommendationOutbox.Status.SENT);
        assertThat(execution.submit(event.eventId()).view()).isNull();
        verify(ai).submit(frozen);
        verify(ai, never()).get(any(), any());
        assertThat(redisTemplate().getExpire("recipe:job:" + event.eventId())).isEqualTo(-1L);
    }

    @Test
    void redisFailureAfterAcceptanceLeavesPendingAndRetriesTheSameRequest() {
        RecommendationSubmissionResult event = event();
        when(ai.submit(any())).thenReturn(receipt(event));
        doThrow(new IllegalStateException("redis unavailable")).doCallRealMethod().when(jobs).accept(eq(event.eventId()), any());
        assertThatThrownBy(() -> execution.submit(event.eventId())).hasMessage("redis unavailable");
        assertThat(repository.findByEventId(event.eventId()).orElseThrow().getStatus()).isEqualTo(RecommendationOutbox.Status.PENDING);
        assertThat(jobs.find(event.eventId())).isEmpty();
        execution.submit(event.eventId());
        verify(ai, times(2)).submit(outbox.request(event.eventId()));
    }

    @Test
    void transportFailureDoesNotInventAFailedAiResultOrMarkTheOutboxSent() {
        RecommendationSubmissionResult event = event();
        when(ai.submit(any())).thenThrow(new RestClientException("unavailable"));
        assertThatThrownBy(() -> execution.submit(event.eventId())).hasMessage("unavailable");
        assertThat(jobs.find(event.eventId())).isEmpty();
        assertThat(repository.findByEventId(event.eventId()).orElseThrow().getStatus()).isEqualTo(RecommendationOutbox.Status.PENDING);
    }

    @Test
    void storesProcessingAndCompleteResultAndDoesNotExtendTerminalRetention() throws Exception {
        RecommendationSubmissionResult event = accepted();
        AiRecipeJobResponse completed = view(event, Status.COMPLETED);
        AiRecipeJobResponse processing = view(event, Status.PROCESSING);
        when(ai.get(eq(receipt(event).jobId()), any())).thenReturn(processing, completed);
        assertThat(execution.refresh(event.eventId()).view().status()).isEqualTo(Status.PROCESSING);
        assertThat(execution.refresh(event.eventId()).view()).isEqualTo(completed);
        String key = "recipe:job:" + event.eventId();
        assertThat(redisTemplate().getExpire(key)).isBetween(86390L, 86400L);
        redisTemplate().expire(key, Duration.ofMinutes(10));
        jobs.update(event.eventId(), view(event, Status.PROCESSING));
        assertThat(execution.refresh(event.eventId()).view()).isEqualTo(completed);
        assertThat(redisTemplate().getExpire(key)).isBetween(590L, 600L);
        verify(ai, times(2)).get(any(), any());
    }

    @Test
    void preservesFailureDetailsAndProcessingCannotRegressToQueued() throws Exception {
        RecommendationSubmissionResult event = accepted();
        jobs.update(event.eventId(), view(event, Status.PROCESSING));
        assertThat(jobs.update(event.eventId(), view(event, Status.QUEUED)).view().status()).isEqualTo(Status.PROCESSING);
        AiRecipeJobResponse failed = view(event, Status.FAILED);
        when(ai.get(any(), any())).thenReturn(failed);
        assertThat(execution.refresh(event.eventId()).view()).isEqualTo(failed);
        assertThat(execution.refresh(event.eventId()).view().error().retryable()).isTrue();
        verify(ai).get(any(), any());
    }

    @Test
    void concurrentRefreshCannotOverwriteCompletionWithProcessing() throws Exception {
        RecommendationSubmissionResult event = accepted();
        AiRecipeJobResponse completed = view(event, Status.COMPLETED);
        AiRecipeJobResponse processing = view(event, Status.PROCESSING);
        CountDownLatch bothReads = new CountDownLatch(2);
        AtomicInteger order = new AtomicInteger();
        when(ai.get(any(), any())).thenAnswer(invocation -> {
            int index = order.getAndIncrement();
            bothReads.countDown();
            assertThat(bothReads.await(10, TimeUnit.SECONDS)).isTrue();
            return index == 0 ? completed : processing;
        });
        assertThat(concurrently(List.of(() -> execution.refresh(event.eventId()), () -> execution.refresh(event.eventId()))))
                .allMatch(value -> value instanceof RecommendationJobStore.Job);
        assertThat(jobs.find(event.eventId()).orElseThrow().view()).isEqualTo(completed);
    }

    @Test
    void databaseFailureAfterRedisAcceptanceDoesNotResubmitAi() {
        RecommendationSubmissionResult event = event();
        when(ai.submit(any())).thenReturn(receipt(event));
        doThrow(new IllegalStateException("DB unavailable")).doCallRealMethod().when(outbox).markSent(event.eventId());
        assertThatThrownBy(() -> execution.submit(event.eventId())).hasMessage("DB unavailable");
        assertThat(jobs.find(event.eventId()).orElseThrow().acceptance()).isEqualTo(receipt(event));
        assertThat(repository.findByEventId(event.eventId()).orElseThrow().getStatus()).isEqualTo(RecommendationOutbox.Status.PENDING);
        execution.submit(event.eventId());
        verify(ai).submit(any());
        assertThat(repository.findByEventId(event.eventId()).orElseThrow().getStatus()).isEqualTo(RecommendationOutbox.Status.SENT);
    }

    @Test
    void refreshTransportFailureKeepsTheLastKnownState() throws Exception {
        RecommendationSubmissionResult event = accepted();
        RecommendationJobStore.Job previous = jobs.update(event.eventId(), view(event, Status.PROCESSING));
        when(ai.get(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new RestClientException("GET unavailable");
        });
        assertThatThrownBy(() -> execution.refresh(event.eventId())).hasMessage("GET unavailable");
        assertThat(jobs.find(event.eventId())).contains(previous);
    }

    @Test
    void rejectsCallsInsideDatabaseTransactionsBeforeReachingAi() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> execution.submit("event")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> execution.refresh("event")))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(ai);
    }

    @Test
    void rejectsAViewFromAnotherJobWithoutChangingStoredState() throws Exception {
        RecommendationSubmissionResult event = accepted();
        AiRecipeJobResponse original = view(event, Status.PROCESSING);
        AiRecipeJobResponse unrelated = new AiRecipeJobResponse("other-job", original.requestId(), original.userId(), original.refrigeratorId(), original.status(),
                original.submittedAt(), original.startedAt(), null, null, null);
        assertThatThrownBy(() -> jobs.update(event.eventId(), unrelated))
                .hasMessageContaining("추천 조회 결과가 접수한 작업과 일치하지 않습니다.").hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.find(event.eventId()).orElseThrow().view()).isNull();
    }

    private RecommendationSubmissionResult event() {
        Fixture owner = ownerFixture("추천실행회원");
        AiRecipeRecommendationRequest request = AiRecipeRecommendationRequest.create("request-" + owner.refrigeratorId(), owner.userId(), owner.refrigeratorId(),
                AiRecipeRecommendationRequest.Mode.EXCLUDE_EXPIRED, List.of(), List.of(), 10);
        return outbox.enqueue(request);
    }

    private RecommendationSubmissionResult accepted() {
        RecommendationSubmissionResult event = event();
        when(ai.submit(any())).thenReturn(receipt(event));
        execution.submit(event.eventId());
        return event;
    }

    private AiRecipeSubmitResponse receipt(RecommendationSubmissionResult event) {
        return new AiRecipeSubmitResponse("rjob-" + event.eventId(), event.requestId(), Status.QUEUED, SUBMITTED);
    }

    private AiRecipeJobResponse view(RecommendationSubmissionResult event, Status status) throws Exception {
        AiRecipeRecommendationRequest request = outbox.request(event.eventId());
        AiRecipeRecommendationResponse result = null;
        if (status == Status.COMPLETED) {
            ObjectNode payload = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
            payload.put("userId", request.userId());
            payload.put("refrigeratorId", event.refrigeratorId()).put("inventoryFingerprint", request.inventoryFingerprint());
            result = mapper.treeToValue(payload, AiRecipeRecommendationResponse.class);
        }
        boolean terminal = status == Status.COMPLETED || status == Status.FAILED;
        AiRecipeJobResponse.Error error = status == Status.FAILED ? new AiRecipeJobResponse.Error("DEPENDENCY_UNAVAILABLE", "재시도 가능", true) : null;
        return new AiRecipeJobResponse(receipt(event).jobId(), event.requestId(), request.userId(), event.refrigeratorId(), status,
                SUBMITTED, SUBMITTED, terminal ? SUBMITTED.plusSeconds(2) : null, result, error);
    }
}
