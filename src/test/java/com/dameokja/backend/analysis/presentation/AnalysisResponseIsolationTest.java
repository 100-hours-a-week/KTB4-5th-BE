package com.dameokja.backend.analysis.presentation;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
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
import org.springframework.test.web.servlet.ResultActions;

class AnalysisResponseIsolationTest extends AnalysisContractTestSupport {
    @Test
    void queuesTheAnalysisAndReturnsAcceptedWithoutWaitingForAi() throws Exception {
        User user = user("접수격리");
        when(ai.ready()).thenReturn(false);
        try (PendingAiResponse delay = new PendingAiResponse()) {
            doAnswer(invocation -> {
                delay.awaitRelease();
                return new AiImageAnalysisSubmitResponse(AI_ANALYSIS_ID, "QUEUED", null, 1000);
            }).when(ai).submit(any());
            Future<ResultActions> response = delay.execute(() -> submitImage(user.getId(), "PRODUCT"));
            String accepted = response.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andReturn().getResponse().getContentAsString();
            String analysisId = mapper.readTree(accepted).path("data").path("analysisId").asString();
            assertAnalysisQueued(analysisId);
            queryAnalysis(user.getId(), analysisId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
        }
    }

    @Test
    void queuesFeedbackAndReturnsCreatedWithoutWaitingForAi() throws Exception {
        Fixture owner = ownerFixture("등록격리");
        String analysisId = completedAnalysis(owner.userId());
        when(ai.ready()).thenReturn(false);
        try (PendingAiResponse delay = new PendingAiResponse()) {
            doAnswer(invocation -> {
                delay.awaitRelease();
                return new AiAnalysisFeedbackResponse("feedback-contract", "RECORDED");
            }).when(ai).feedback(any(), any());
            Future<ResultActions> registration = delay.execute(() -> registerInventory(owner.userId(), owner.refrigeratorId(), analysisId));
            registration.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.createdCount").value(1));
            assertFeedbackQueued(owner.refrigeratorId());
            queryInventory(owner.userId(), owner.refrigeratorId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ingredients[0].name").value("우유"))
                    .andExpect(jsonPath("$.data.ingredients[0].quantity").value(2));
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

    private static final class PendingAiResponse implements AutoCloseable {
        private final CountDownLatch released = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        <R> Future<R> execute(Callable<R> task) { return executor.submit(task); }

        void awaitRelease() throws InterruptedException { released.await(); }

        @Override
        public void close() {
            released.countDown();
            executor.close();
        }
    }
}
