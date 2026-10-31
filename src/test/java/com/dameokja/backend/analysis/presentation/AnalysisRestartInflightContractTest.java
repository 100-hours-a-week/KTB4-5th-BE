package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackOutboxWorker;
import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackProperties;
import com.dameokja.backend.analysis.application.outbox.AnalysisOutboxWorker;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxProperties;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisFeedbackSubmittedEvent;
import com.dameokja.backend.analysis.support.RestartableAnalysisContractTestSupport;
import com.dameokja.backend.user.domain.User;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AnalysisRestartInflightContractTest extends RestartableAnalysisContractTestSupport {
    @Autowired AnalysisOutboxWorker analysisOutboxWorker;
    @Autowired AnalysisOutboxProperties analysisOutboxProperties;
    @Autowired AnalysisFeedbackOutboxWorker analysisFeedbackOutboxWorker;
    @Autowired AnalysisFeedbackProperties analysisFeedbackProperties;

    @Test
    void recoversTheSameAnalysisEventIfTheApplicationStopsBeforeRecordingPublication() throws Exception {
        User requestingUser = user("발행중단복구");
        acceptAnalysis(requestingUser.getId());
        when(analysisKafkaPublisher.publish(any(AnalysisOutbox.class))).thenThrow(new BackendInterrupted());
        assertThatThrownBy(analysisOutboxWorker::publishBatch).isInstanceOf(BackendInterrupted.class);
        String originalMessageId = jdbcTemplate.queryForObject("SELECT message_id FROM analysis_outbox", String.class);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_outbox", String.class)).isEqualTo("PUBLISHING");
        restartApplication();
        clock.set(clock.instant().plus(analysisOutboxProperties.leaseDuration()).plusSeconds(1).toString());
        when(analysisKafkaPublisher.publish(any(AnalysisOutbox.class))).thenReturn(CompletableFuture.completedFuture(null));
        analysisOutboxWorker.publishBatch();
        ArgumentCaptor<AnalysisOutbox> publishedOutbox = ArgumentCaptor.forClass(AnalysisOutbox.class);
        verify(analysisKafkaPublisher).publish(publishedOutbox.capture());
        assertThat(publishedOutbox.getValue().getMessageId()).isEqualTo(originalMessageId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_outbox", String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    void recoversTheSameFeedbackEventWithoutRegisteringInventoryAgain() throws Exception {
        Fixture owner = ownerFixture("피드백중단복구");
        String analysisJobId = acceptAnalysis(owner.userId());
        completeStoredAnalysis(analysisJobId, owner.userId());
        registerInventory(owner.userId(), owner.refrigeratorId(), analysisJobId).andExpect(status().isCreated());
        Instant inventoryObservedAt = clock.instant();
        JsonNode inventoryBeforeRestart = inventory(owner);
        when(analysisKafkaPublisher.publishFeedback(any())).thenThrow(new BackendInterrupted());
        assertThatThrownBy(analysisFeedbackOutboxWorker::sendDue).isInstanceOf(BackendInterrupted.class);
        AnalysisFeedbackSubmittedEvent storedEvent = mapper.readValue(jdbcTemplate.queryForObject("SELECT feedback_body FROM analysis_feedbacks", String.class),
                AnalysisFeedbackSubmittedEvent.class);
        restartApplication();
        clock.set(clock.instant().plus(analysisFeedbackProperties.leaseDuration()).plusSeconds(1).toString());
        when(analysisKafkaPublisher.publishFeedback(any())).thenReturn(CompletableFuture.completedFuture(null));
        analysisFeedbackOutboxWorker.sendDue();
        analysisFeedbackOutboxWorker.sendDue();
        verify(analysisKafkaPublisher).publishFeedback(storedEvent);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_feedbacks", String.class)).isEqualTo("DONE");
        clock.set(inventoryObservedAt.toString());
        assertThat(inventory(owner)).isEqualTo(inventoryBeforeRestart);
    }

    private JsonNode inventory(Fixture owner) throws Exception {
        String response = queryInventory(owner.userId(), owner.refrigeratorId()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }

    // 발행 뒤 DB 기록·재예약이 실행되지 않은 프로세스 중단을 재현한다.
    private static final class BackendInterrupted extends Error {}
}
