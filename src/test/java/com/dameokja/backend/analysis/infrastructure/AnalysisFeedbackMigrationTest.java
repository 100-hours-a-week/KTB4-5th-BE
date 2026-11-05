package com.dameokja.backend.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.support.MySqlJpaTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisFeedbackMigrationTest extends MySqlJpaTest {
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS analysis_feedbacks_migration_test");
    }

    @Test
    void migrationCreatesPendingFeedbackStorageWithLeaseConstraint() throws Exception {
        String migration = new ClassPathResource("db/migrations/20261019-analysis-feedback-storage.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("analysis_feedbacks", "analysis_feedbacks_migration_test")
                .replace("ck_analysis_feedback_", "ck_feedback_migration_");
        jdbcTemplate.execute(migration);
        jdbcTemplate.update("""
                INSERT INTO analysis_feedbacks_migration_test
                    (ai_analysis_id, item_id, feedback_body, next_attempt_at, expires_at, created_at, updated_at)
                VALUES ('ai-1', 'item-1', '{"itemId":"item-1"}',
                        '2026-10-19 10:00:00', '2026-10-22 10:00:00', '2026-10-19 10:00:00', '2026-10-19 10:00:00')
                """);
        assertThat(jdbcTemplate.queryForMap("SELECT status, attempt_count FROM analysis_feedbacks_migration_test"))
                .containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE analysis_feedbacks_migration_test SET status = 'SENDING'"))
                .isInstanceOf(UncategorizedSQLException.class).hasMessageContaining("ck_feedback_migration_lease");
    }

    @Test
    void messageBodyMigrationPreservesExistingHttpRowsAndTheirDeliveryState() throws Exception {
        jdbcTemplate.execute("CREATE TABLE analysis_feedbacks_migration_test LIKE analysis_feedbacks");
        jdbcTemplate.execute("ALTER TABLE analysis_feedbacks_migration_test DROP COLUMN message_body");
        jdbcTemplate.update("""
                INSERT INTO analysis_feedbacks_migration_test
                    (ai_analysis_id, feedback_body, status, attempt_count, next_attempt_at, lease_token, lease_until,
                     expires_at, finished_at, created_at, updated_at)
                VALUES ('pending', '{"refrigeratorId":17,"items":[]}', 'PENDING', 2, NOW(6), NULL, NULL, NOW(6), NULL, NOW(6), NOW(6)),
                       ('sending', '{"refrigeratorId":17,"items":[]}', 'SENDING', 1, NOW(6), 'owner', NOW(6), NOW(6), NULL, NOW(6), NOW(6)),
                       ('done', '{"refrigeratorId":17,"items":[]}', 'DONE', 0, NOW(6), NULL, NULL, NOW(6), NOW(6), NOW(6), NOW(6))
                """);
        String query = "SELECT analysis_feedback_id, ai_analysis_id, feedback_body, status, attempt_count, next_attempt_at, lease_token, lease_until, "
                + "expires_at, finished_at, created_at, updated_at FROM analysis_feedbacks_migration_test ORDER BY analysis_feedback_id";
        List<Map<String, Object>> before = jdbcTemplate.queryForList(query);
        String migration = new ClassPathResource("db/migrations/20261104-analysis-feedback-message-body.sql")
                .getContentAsString(StandardCharsets.UTF_8).replace("analysis_feedbacks", "analysis_feedbacks_migration_test");
        jdbcTemplate.execute(migration);
        assertThat(jdbcTemplate.queryForList(query)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks_migration_test WHERE message_body IS NULL", Integer.class)).isEqualTo(3);
    }
}
