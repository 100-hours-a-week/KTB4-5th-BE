package com.dameokja.backend.analysis.application.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.AnalysisFeedbackRepository;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import({AnalysisFeedbackQueue.class, AnalysisFeedbackQueueIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisFeedbackTransportIsolationTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = BusinessTime.now(Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), BusinessTime.ZONE));
    private static final String HTTP_BODY = "{\"refrigeratorId\":17,\"consentForModelImprovement\":true,\"items\":[]}";
    private static final String KAFKA_BODY = "{\"messageId\":\"feedback-message\",\"payload\":{\"eventType\":\"AnalysisFeedbackSubmitted\"}}";
    @Autowired AnalysisFeedbackQueue queue;
    @Autowired AnalysisFeedbackRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;
    private final List<Long> feedbackIds = new ArrayList<>();

    @AfterEach
    void cleanUp() { repository.deleteAllById(feedbackIds); }

    @Test
    void leasesOnlyHttpFeedbackWhenBothTransportsArePending() {
        Long httpId = saveHttp();
        Long kafkaId = saveKafka();
        assertThat(queue.leaseDue()).extracting(LeasedFeedback::id).containsExactly(httpId);
        assertThat(status(kafkaId)).isEqualTo("PENDING");
        assertThat(queue.leaseDue()).isEmpty();
    }

    @Test
    void reclaimsOnlyExpiredHttpLeases() {
        Long httpId = saveHttp();
        Long kafkaId = saveKafka();
        abandon(httpId);
        abandon(kafkaId);
        assertThat(queue.findLeased(new LeasedFeedback(kafkaId, "previous-owner"))).isEmpty();
        List<LeasedFeedback> leased = queue.leaseDue();
        assertThat(leased).extracting(LeasedFeedback::id).containsExactly(httpId);
        queue.finish(leased.getFirst());
        assertThat(status(httpId)).isEqualTo("DONE");
        assertThat(status(kafkaId)).isEqualTo("SENDING");
        assertThat(queue.leaseDue()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SENDING"})
    void expiresOnlyHttpFeedback(String status) {
        Long httpId = saveHttp();
        Long kafkaId = saveKafka();
        if (status.equals("SENDING")) {
            abandon(httpId);
            abandon(kafkaId);
        }
        feedbackIds.forEach(id -> jdbcTemplate.update("UPDATE analysis_feedbacks SET expires_at = ? WHERE analysis_feedback_id = ?", NOW, id));
        queue.failExpired();
        assertThat(status(httpId)).isEqualTo("FAILED");
        assertThat(status(kafkaId)).isEqualTo(status);
        assertThat(jdbcTemplate.queryForObject("SELECT last_error_code FROM analysis_feedbacks WHERE analysis_feedback_id = ?", String.class, kafkaId)).isNull();
    }

    private Long saveHttp() {
        return save(AnalysisFeedback.pending("ai-http", HTTP_BODY, NOW, NOW.plusDays(3)));
    }

    private Long saveKafka() {
        return save(AnalysisFeedback.pendingKafka("ai-kafka", HTTP_BODY, KAFKA_BODY, NOW, NOW.plusDays(3)));
    }

    private Long save(AnalysisFeedback feedback) {
        Long id = repository.saveAndFlush(feedback).getId();
        feedbackIds.add(id);
        return id;
    }

    private void abandon(Long id) {
        jdbcTemplate.update("UPDATE analysis_feedbacks SET status = 'SENDING', lease_token = 'previous-owner', lease_until = ? WHERE analysis_feedback_id = ?", NOW, id);
    }

    private String status(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM analysis_feedbacks WHERE analysis_feedback_id = ?", String.class, id);
    }
}
