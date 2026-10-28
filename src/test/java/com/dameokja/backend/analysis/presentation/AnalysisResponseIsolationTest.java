package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackResponse;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.support.AnalysisContractTestSupport;
import com.dameokja.backend.user.domain.User;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.test.web.servlet.ResultActions;

class AnalysisResponseIsolationTest extends AnalysisContractTestSupport {
    @Test
    void acceptsAndQueriesAnAnalysisWhileAiAcceptanceIsStillBlocked() throws Exception {
        User user = user("접수격리");
        try (DelayedResponse<AiImageAnalysisSubmitResponse> delay = new DelayedResponse<>(
                new AiImageAnalysisSubmitResponse(AI_ANALYSIS_ID, "QUEUED", null, 1000))) {
            doAnswer(delay).when(ai).submit(any());
            Future<ResultActions> response = delay.execute(() -> submitImage(user.getId(), "PRODUCT"));
            delay.awaitRequest();
            String accepted = response.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andReturn().getResponse().getContentAsString();
            String analysisId = mapper.readTree(accepted).path("data").path("analysisId").asString();
            queryAnalysis(user.getId(), analysisId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));

            delay.release();
            awaitProcessing(user.getId(), analysisId);
            completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
            queryAnalysis(user.getId(), analysisId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        }
    }

    @Test
    void registersAndQueriesInventoryWhileAiFeedbackIsStillBlocked() throws Exception {
        Fixture owner = ownerFixture("등록격리");
        String analysisId = completedAnalysis(owner.userId());
        try (DelayedResponse<AiAnalysisFeedbackResponse> delay = new DelayedResponse<>(new AiAnalysisFeedbackResponse("feedback-contract", "RECORDED"))) {
            doAnswer(delay).when(ai).feedback(any(), any());
            Future<ResultActions> registration = delay.execute(() -> registerInventory(owner.userId(), owner.refrigeratorId(), analysisId));
            registration.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.createdCount").value(1));
            Future<?> delivery = delay.execute(() -> {
                deliverFeedbacks();
                return null;
            });
            delay.awaitRequest();

            queryInventory(owner.userId(), owner.refrigeratorId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ingredients[0].name").value("우유"))
                    .andExpect(jsonPath("$.data.ingredients[0].quantity").value(2));
            delay.release();
            delivery.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private String completedAnalysis(Long userId) throws Exception {
        String response = submitImage(userId, "PRODUCT").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String analysisId = mapper.readTree(response).path("data").path("analysisId").asString();
        awaitProcessing(userId, analysisId);
        completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
        return analysisId;
    }

    private void awaitProcessing(Long userId, String analysisId) {
        await().atMost(WAIT_LIMIT).untilAsserted(() -> queryAnalysis(userId, analysisId)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING")));
    }

    private static final class DelayedResponse<T> implements Answer<T>, AutoCloseable {
        private final CountDownLatch requested = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final T response;

        DelayedResponse(T response) { this.response = response; }

        <R> Future<R> execute(Callable<R> task) { return executor.submit(task); }

        void awaitRequest() throws InterruptedException {
            assertThat(requested.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).as("AI 응답 대기 진입").isTrue();
        }

        void release() { released.countDown(); }

        @Override
        public T answer(InvocationOnMock invocation) throws InterruptedException {
            requested.countDown();
            assertThat(released.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).as("AI 응답 대기 해제").isTrue();
            return response;
        }

        @Override
        public void close() throws InterruptedException {
            release();
            executor.shutdown();
            if (!executor.awaitTermination(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).as("테스트 실행기 종료").isTrue();
            }
        }
    }
}
