package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackResponse;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisResponse;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisResult;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.support.RestartableAnalysisContractTestSupport;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

class AnalysisRestartInflightContractTest extends RestartableAnalysisContractTestSupport {
    @Test
    void resendsTheSameAnalysisWhenTheBackendStopsBeforeSavingTheAiAcceptance() throws Exception {
        when(ai.ready()).thenReturn(false);
        Long userId = ownerFixture("접수중단복구").userId();
        String analysisId = accept(userId);
        doAnswer(invocation -> { throw new BackendInterrupted(); }).when(ai).submit(any());
        assertThatThrownBy(this::dispatchDueAnalyses).isInstanceOf(BackendInterrupted.class);
        ArgumentCaptor<AiImageAnalysisSubmitRequest> sent = ArgumentCaptor.forClass(AiImageAnalysisSubmitRequest.class);
        verify(ai).submit(sent.capture());
        AiImageAnalysisSubmitRequest interruptedRequest = sent.getValue();

        restartApplication();
        recoverInterruptedAnalyses();

        expectState(userId, analysisId, "PROCESSING");
        verify(ai).submit(argThat(request -> request.requestId().equals(interruptedRequest.requestId())));
        completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
        expectState(userId, analysisId, "COMPLETED");
        pollAnalyses();
        verify(ai).submit(any());
        verify(ai, never()).get(any());
    }

    @Test
    void recoversAnAcceptedAnalysisWithoutItsCallbackAfterTheBackendRestarts() throws Exception {
        when(ai.ready()).thenReturn(false);
        Long userId = ownerFixture("진행중복구").userId();
        String analysisId = accept(userId);
        dispatchDueAnalyses();
        expectState(userId, analysisId, "PROCESSING");

        restartApplication();
        when(ai.get(AI_ANALYSIS_ID)).thenReturn(finishedAnalysis());
        recoverInterruptedAnalyses();

        expectState(userId, analysisId, "COMPLETED");
        pollAnalyses();
        verify(ai).get(AI_ANALYSIS_ID);
        verify(ai, never()).submit(any());
    }

    @Test
    void resendsInterruptedFeedbackWithoutRegisteringInventoryAgain() throws Exception {
        when(ai.ready()).thenReturn(false);
        Fixture owner = ownerFixture("피드백중단복구");
        String analysisId = accept(owner.userId());
        dispatchDueAnalyses();
        completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
        registerInventory(owner.userId(), owner.refrigeratorId(), analysisId).andExpect(status().isCreated());
        Instant inventoryObservedAt = clock.instant();
        JsonNode inventoryBeforeRestart = inventory(owner);
        when(ai.ready()).thenReturn(true);
        doAnswer(invocation -> { throw new BackendInterrupted(); }).when(ai).feedback(any(), any());
        assertThatThrownBy(this::sendFeedback).isInstanceOf(BackendInterrupted.class);
        ArgumentCaptor<AiAnalysisFeedbackRequest> sent = ArgumentCaptor.forClass(AiAnalysisFeedbackRequest.class);
        verify(ai).feedback(eq(AI_ANALYSIS_ID), sent.capture());
        AiAnalysisFeedbackRequest interruptedFeedback = sent.getValue();

        restartApplication();
        when(ai.feedback(any(), any())).thenReturn(new AiAnalysisFeedbackResponse("feedback-recovered", "ACCEPTED"));
        recoverInterruptedFeedback();

        verify(ai).feedback(AI_ANALYSIS_ID, interruptedFeedback);
        recoverInterruptedFeedback();
        verify(ai).feedback(any(), any());
        clock.set(inventoryObservedAt.toString());
        assertThat(inventory(owner)).isEqualTo(inventoryBeforeRestart);
    }

    private String accept(Long userId) throws Exception {
        String body = submitImage(userId, "PRODUCT").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).at("/data/analysisId").asString();
    }

    private void expectState(Long userId, String analysisId, String expected) {
        await().atMost(WAIT_LIMIT).untilAsserted(() -> queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId))
                .andExpect(jsonPath("$.data.status").value(expected)));
    }

    private AiImageAnalysisResponse finishedAnalysis() {
        AiImageAnalysisResult result = new AiImageAnalysisResult(new AiImageAnalysisResult.DocumentType("PRODUCT", 1), null, List.of(), null);
        return new AiImageAnalysisResponse(AI_ANALYSIS_ID, "COMPLETED", null, null, null,
                clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(), result, null);
    }

    private JsonNode inventory(Fixture owner) throws Exception {
        String body = queryInventory(owner.userId(), owner.refrigeratorId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data");
    }

    // AI가 요청을 받은 뒤 BE의 후속 저장·재예약이 실행되지 않은 중단점을 만든다.
    private static final class BackendInterrupted extends Error {}
}
