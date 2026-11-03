package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.dameokja.backend.analysis.application.outbox.AnalysisOutboxWorker;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.support.RestartableAnalysisContractTestSupport;
import com.dameokja.backend.user.domain.User;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AnalysisRestartQueuedContractTest extends RestartableAnalysisContractTestSupport {
    @Autowired AnalysisOutboxWorker analysisOutboxWorker;

    @Test
    void publishesTheOriginalUnsentEventAfterTheApplicationRestarts() throws Exception {
        User requestingUser = user("대기복구계약");
        String analysisJobId = acceptAnalysis(requestingUser.getId());
        String storedEvent = jdbcTemplate.queryForObject("SELECT message_body FROM analysis_outbox", String.class);
        restartApplication();
        queryAnalysis(requestingUser.getId(), analysisJobId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
        when(analysisKafkaPublisher.publish(any(AnalysisOutbox.class))).thenReturn(CompletableFuture.completedFuture(null));
        analysisOutboxWorker.publishBatch();
        ArgumentCaptor<AnalysisOutbox> publishedOutbox = ArgumentCaptor.forClass(AnalysisOutbox.class);
        verify(analysisKafkaPublisher).publish(publishedOutbox.capture());
        assertThat(mapper.readTree(publishedOutbox.getValue().getMessageBody())).isEqualTo(mapper.readTree(storedEvent));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_outbox", String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    void returnsTheStoredResultAndSummaryAfterRestartWithoutPublishingAgain() throws Exception {
        User requestingUser = user("결과복구계약");
        String analysisJobId = acceptAnalysis(requestingUser.getId());
        completeStoredAnalysis(analysisJobId, requestingUser.getId());
        JsonNode resultBeforeRestart = result(requestingUser.getId(), analysisJobId);
        JsonNode summaryBeforeRestart = summary(requestingUser.getId());
        restartApplication();
        assertThat(result(requestingUser.getId(), analysisJobId)).isEqualTo(resultBeforeRestart);
        assertThat(summary(requestingUser.getId())).isEqualTo(summaryBeforeRestart);
        org.mockito.Mockito.verifyNoInteractions(analysisKafkaPublisher);
    }

    private JsonNode result(Long userId, String analysisJobId) throws Exception {
        String response = queryAnalysis(userId, analysisJobId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }

    private JsonNode summary(Long userId) throws Exception {
        String response = listAnalyses(userId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }
}
