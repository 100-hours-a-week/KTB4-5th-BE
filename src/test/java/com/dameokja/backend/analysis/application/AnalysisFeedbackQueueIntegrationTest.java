package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisFeedbackRequest;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.support.MySqlJpaTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({AnalysisFeedbackQueue.class, AnalysisFeedbackSender.class, AnalysisFeedbackQueueIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisFeedbackQueueIntegrationTest extends MySqlJpaTest {
    private static final Duration RETRY_DELAY = Duration.ofMinutes(10);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration EXPIRES_AFTER = Duration.ofDays(3);
    private static final Instant START = Instant.parse("2026-10-18T12:00:00Z");
    private final AiAnalysisFeedbackRequest feedbackRequest = AiAnalysisFeedbackRequest.corrected("item-1", AiAnalysisFeedbackRequest.Confirmed.userConfirmed(
            "우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.WEIGHT, null, new BigDecimal("10.5"), WeightUnit.ML, LocalDate.parse("2026-10-20")));

    @Autowired AnalysisFeedbackQueue analysisFeedbackQueue;
    @Autowired AnalysisFeedbackSender analysisFeedbackSender;
    @Autowired AiAnalysisClient aiAnalysisClient;
    @Autowired MovableClock clock;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        reset(aiAnalysisClient);
        clock.moveTo(START);
        when(aiAnalysisClient.ready()).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM analysis_feedbacks");
    }

    @Test
    void sendsConfirmedValuesAndFinishes() {
        analysisFeedbackQueue.enqueue("ai-1", feedbackRequest);
        analysisFeedbackSender.sendDue();
        verify(aiAnalysisClient).feedback("ai-1", feedbackRequest);
        assertThat(row()).containsEntry("status", "DONE").containsEntry("attempt_count", 0);
        analysisFeedbackSender.sendDue();
        verify(aiAnalysisClient).feedback(any(), any());
    }

    @Test
    void waitsWithoutUsingAttemptsWhileAiIsDown() {
        analysisFeedbackQueue.enqueue("ai-1", feedbackRequest);
        when(aiAnalysisClient.ready()).thenReturn(false);
        analysisFeedbackSender.sendDue();
        verify(aiAnalysisClient, never()).feedback(any(), any());
        assertThat(row()).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        when(aiAnalysisClient.ready()).thenReturn(true);
        clock.moveTo(START.plus(Duration.ofHours(10)));
        analysisFeedbackSender.sendDue();
        assertThat(row()).containsEntry("status", "DONE");
    }

    @Test
    void retriesAfterDelayWhenSendingFails() {
        analysisFeedbackQueue.enqueue("ai-1", feedbackRequest);
        when(aiAnalysisClient.feedback(eq("ai-1"), any())).thenThrow(new ResourceAccessException("connection refused")).thenReturn(null);
        analysisFeedbackSender.sendDue();
        assertThat(row()).containsEntry("status", "PENDING").containsEntry("attempt_count", 1).containsEntry("last_error_code", "AI_UNAVAILABLE");
        clock.moveTo(START.plus(RETRY_DELAY).minusSeconds(1));
        analysisFeedbackSender.sendDue();
        assertThat(row()).containsEntry("status", "PENDING");
        clock.moveTo(START.plus(RETRY_DELAY));
        analysisFeedbackSender.sendDue();
        assertThat(row()).containsEntry("status", "DONE").containsEntry("last_error_code", null);
    }

    @Test
    void failsRejectedFeedbackButRetriesTooManyRequests() {
        analysisFeedbackQueue.enqueue("ai-busy", feedbackRequest);
        analysisFeedbackQueue.enqueue("ai-gone", feedbackRequest);
        when(aiAnalysisClient.feedback(eq("ai-busy"), any())).thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "busy", null, null, null));
        when(aiAnalysisClient.feedback(eq("ai-gone"), any())).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "gone", null, null, null));
        analysisFeedbackSender.sendDue();
        assertThat(rowOf("ai-busy")).containsEntry("status", "PENDING").containsEntry("last_error_code", "AI_BUSY");
        assertThat(rowOf("ai-gone")).containsEntry("status", "FAILED").containsEntry("last_error_code", "AI_REJECTED");
    }

    @Test
    void resendsWhenServerStoppedWhileSending() {
        analysisFeedbackQueue.enqueue("ai-1", feedbackRequest);
        assertThat(analysisFeedbackQueue.leaseDue()).hasSize(1);
        analysisFeedbackSender.sendDue();
        verify(aiAnalysisClient, never()).feedback(any(), any());
        clock.moveTo(START.plus(LEASE_DURATION));
        analysisFeedbackSender.sendDue();
        assertThat(row()).containsEntry("status", "DONE");
    }

    @Test
    void stopsSendingAfterExpiration() {
        analysisFeedbackQueue.enqueue("ai-1", feedbackRequest);
        when(aiAnalysisClient.ready()).thenReturn(false);
        clock.moveTo(START.plus(EXPIRES_AFTER));
        analysisFeedbackSender.sendDue();
        when(aiAnalysisClient.ready()).thenReturn(true);
        analysisFeedbackSender.sendDue();
        verify(aiAnalysisClient, never()).feedback(any(), any());
        assertThat(row()).containsEntry("status", "FAILED").containsEntry("last_error_code", "EXPIRED");
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("SELECT status, attempt_count, last_error_code FROM analysis_feedbacks");
    }

    private Map<String, Object> rowOf(String aiAnalysisId) {
        return jdbcTemplate.queryForMap("SELECT status, attempt_count, last_error_code FROM analysis_feedbacks WHERE ai_analysis_id = ?", aiAnalysisId);
    }

    static class MovableClock extends Clock {
        private Instant now = START;

        void moveTo(Instant instant) { now = instant; }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }

        @Override
        public Instant instant() { return now; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        MovableClock feedbackClock() {
            return new MovableClock();
        }

        @Bean
        AnalysisFeedbackProperties analysisFeedbackProperties() {
            return new AnalysisFeedbackProperties(RETRY_DELAY, LEASE_DURATION, EXPIRES_AFTER, 20);
        }

        @Bean
        AiAnalysisClient aiAnalysisClient() {
            return mock(AiAnalysisClient.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
