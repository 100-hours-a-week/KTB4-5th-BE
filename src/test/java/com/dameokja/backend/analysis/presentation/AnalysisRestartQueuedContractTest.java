package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.support.RestartableAnalysisContractTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AnalysisRestartQueuedContractTest extends RestartableAnalysisContractTestSupport {
    @Test
    void recoversAnAcceptedButUnsentAnalysisAfterTheApplicationRestarts() throws Exception {
        when(ai.ready()).thenReturn(false);
        Long userId = ownerFixture("대기복구계약").userId();
        String analysisId = accept(userId);
        assertAnalysisQueued(analysisId);
        verify(ai, never()).submit(any());

        restartApplication();

        queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        dispatchDueAnalyses();
        verify(ai).submit(any());
        queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
        assertCompleted(userId, analysisId);
    }

    @Test
    void returnsTheStoredResultAndSummaryAfterRestartWithoutCallingAiAgain() throws Exception {
        when(ai.ready()).thenReturn(false);
        Long userId = ownerFixture("결과복구계약").userId();
        String analysisId = accept(userId);
        dispatchDueAnalyses();
        completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
        JsonNode resultBeforeRestart = assertCompleted(userId, analysisId);
        JsonNode summaryBeforeRestart = summary(userId, analysisId);

        restartApplication();
        when(ai.ready()).thenReturn(false);

        assertThat(assertCompleted(userId, analysisId)).isEqualTo(resultBeforeRestart);
        assertThat(summary(userId, analysisId)).isEqualTo(summaryBeforeRestart);
        verify(ai, never()).submit(any());
        verify(ai, never()).get(any());
    }

    private String accept(Long userId) throws Exception {
        String body = submitImage(userId, "PRODUCT").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).at("/data/analysisId").asString();
    }

    private JsonNode assertCompleted(Long userId, String analysisId) throws Exception {
        String body = queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data");
    }

    private JsonNode summary(Long userId, String analysisId) throws Exception {
        String body = listAnalyses(userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyses[*].analysisId").value(hasItem(analysisId)))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data");
    }
}
