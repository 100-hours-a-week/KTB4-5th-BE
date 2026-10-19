package com.dameokja.backend.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.domain.AnalysisFeedbackStatus;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class AnalysisFeedbackRepositoryTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = BusinessTime.now(Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), BusinessTime.ZONE));
    private static final String BODY = """
            {"itemId":"item-1","action":"CORRECTED","consentForModelImprovement":true,
             "confirmed":{"name":"우유","category":"DAIRY","storageType":"REFRIGERATED",
                          "measureType":"WEIGHT","quantity":null,"weightValue":10.5,
                          "weightUnit":"ML","expirationDate":"2026-10-20","expirationSource":"USER_CONFIRMED"}}
            """;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AnalysisFeedbackRepository repository;

    @Test
    void reloadsConfirmedPayloadAndPendingSchedule() {
        AnalysisFeedback saved = repository.saveAndFlush(AnalysisFeedback.pending("ai-1", "item-1", BODY, NOW, NOW.plusDays(3)));
        flushAndClear();
        AnalysisFeedback found = repository.findById(saved.getId()).orElseThrow();
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(found.getFeedbackBody())).isEqualTo(mapper.readTree(BODY));
        assertThat(found.getAiAnalysisId()).isEqualTo("ai-1");
        assertThat(found.getItemId()).isEqualTo("item-1");
        assertThat(found.getStatus()).isEqualTo(AnalysisFeedbackStatus.PENDING);
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(found.getExpiresAt()).isEqualTo(NOW.plusDays(3));
        assertThat(found.getLeaseToken()).isNull();
        assertThat(found.getLeaseUntil()).isNull();
        assertThat(found.getLastErrorCode()).isNull();
        assertThat(found.getFinishedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void storesConfirmedPayloadAsJson() {
        insertPending();
        String stored = jdbcTemplate.queryForObject("SELECT feedback_body FROM analysis_feedbacks", String.class);
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(stored)).isEqualTo(mapper.readTree(BODY));
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN, ck_analysis_feedback_status", "SENDING, ck_analysis_feedback_lease",
            "DONE, ck_analysis_feedback_finished", "FAILED, ck_analysis_feedback_finished"})
    void rejectsInvalidOrIncompleteStatus(String status, String constraintName) {
        insertPending();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE analysis_feedbacks SET status = ?", status))
                .isInstanceOf(UncategorizedSQLException.class).hasMessageContaining(constraintName);
    }

    @Test
    void rejectsNegativeAttempts() {
        insertPending();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE analysis_feedbacks SET attempt_count = -1"))
                .isInstanceOf(UncategorizedSQLException.class).hasMessageContaining("ck_analysis_feedback_attempts");
    }

    private void insertPending() {
        jdbcTemplate.update("""
                INSERT INTO analysis_feedbacks
                    (ai_analysis_id, item_id, feedback_body, next_attempt_at, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, "ai-1", "item-1", BODY, NOW, NOW.plusDays(3), NOW, NOW);
    }
}
