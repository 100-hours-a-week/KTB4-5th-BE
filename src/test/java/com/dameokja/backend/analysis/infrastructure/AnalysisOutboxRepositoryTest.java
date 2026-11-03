package com.dameokja.backend.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.domain.AnalysisOutboxStatus;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class AnalysisOutboxRepositoryTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);
    private static final String NOW_SQL = "2026-11-04 12:00:00";
    @Autowired AnalysisOutboxRepository outbox;
    @Autowired AnalysisJobStateRepository states;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void storesTheFixedMessageAndRejectsDuplicateMessageIds() {
        AnalysisOutbox saved = pending(NOW);
        flushAndClear();
        AnalysisOutbox restored = outbox.findById(saved.getId()).orElseThrow();
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(restored.getMessageBody())).isEqualTo(mapper.readTree(saved.getMessageBody()));
        assertThat(restored.getMessageKey()).isEqualTo(saved.getAnalysisJobState().getUser().getId().toString());
        assertThat(restored.getMessageId()).isEqualTo(saved.getMessageId());
        assertThat(restored.getStatus()).isEqualTo(AnalysisOutboxStatus.PENDING);
        assertThat(restored.getAttemptCount()).isZero();
        assertThat(outbox.findIdsByAnalysisJobId(saved.getAnalysisJobState().getId())).containsExactly(saved.getId());
        assertThatThrownBy(() -> outbox.saveAndFlush(AnalysisOutbox.pending(restored.getAnalysisJobState(),
                saved.getMessageId(), saved.getTopic(), saved.getMessageBody(), NOW))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsExpiredAndWrongLeaseAcknowledgementsAndRecordsValidPublication() {
        AnalysisOutbox saved = pending(NOW);
        saved.lease("current-owner", NOW.plusMinutes(1));
        flushAndClear();
        assertThat(outbox.markPublished(saved.getId(), "previous-owner", NOW_SQL)).isZero();
        assertThat(outbox.markPublished(saved.getId(), "current-owner", "2026-11-04 12:01:00")).isZero();
        assertThat(outbox.findExpiredLeaseForUpdate("2026-11-04 12:00:59", 1)).isEmpty();
        assertThat(outbox.findExpiredLeaseForUpdate("2026-11-04 12:01:00", 1)).extracting(AnalysisOutbox::getId).containsExactly(saved.getId());
        assertThat(outbox.markPublished(saved.getId(), "current-owner", NOW_SQL)).isEqualTo(1);
        flushAndClear();
        AnalysisOutbox published = outbox.findById(saved.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isEqualTo(NOW);
        assertThat(published.getLeaseToken()).isNull();
        assertThat(published.getLeaseUntil()).isNull();
        assertThat(published.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void retriesOnlyForTheCurrentOwnerAndDoesNotSelectBeforeTheRetryTime() {
        AnalysisOutbox saved = pending(NOW);
        saved.lease("current-owner", NOW.plusMinutes(1));
        flushAndClear();
        assertThat(outbox.retryLater(saved.getId(), "previous-owner", NOW_SQL, "2026-11-04 12:00:05")).isZero();
        assertThat(outbox.retryLater(saved.getId(), "current-owner", "2026-11-04 12:01:00", "2026-11-04 12:01:05")).isZero();
        assertThat(outbox.retryLater(saved.getId(), "current-owner", NOW_SQL, "2026-11-04 12:00:05")).isEqualTo(1);
        flushAndClear();
        assertThat(outbox.findPendingForUpdate(NOW_SQL, 1)).isEmpty();
        assertThat(outbox.findPendingForUpdate("2026-11-04 12:00:05", 1)).extracting(AnalysisOutbox::getId).containsExactly(saved.getId());
        AnalysisOutbox retried = outbox.findById(saved.getId()).orElseThrow();
        assertThat(retried.getMessageId()).isEqualTo(saved.getMessageId());
        assertThat(retried.getLastErrorCode()).isEqualTo("PUBLISH_FAILED");
        assertThat(retried.getLeaseToken()).isNull();
        assertThat(retried.getLeaseUntil()).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void skipsMessagesLockedByAnotherOpenTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        List<AnalysisOutbox> messages = transaction.execute(status -> List.of(pending(NOW), pending(NOW)));
        try {
            transaction.executeWithoutResult(first -> {
                assertThat(outbox.findPendingForUpdate(NOW_SQL, 1)).extracting(AnalysisOutbox::getId).containsExactly(messages.getFirst().getId());
                transaction.executeWithoutResult(second -> {
                    Integer lockTimeout = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    try {
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 2");
                        assertThat(outbox.findPendingForUpdate(NOW_SQL, 1)).extracting(AnalysisOutbox::getId).containsExactly(messages.getLast().getId());
                    } finally {
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + lockTimeout);
                    }
                });
            });
        } finally {
            transaction.executeWithoutResult(status -> cleanUp(messages));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migrationCreatesOutboxStorageWithoutChangingExistingTables() throws Exception {
        String migration = new ClassPathResource("db/migrations/20261104-analysis-outbox-storage.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("CREATE TABLE analysis_outbox (", "CREATE TABLE analysis_outbox_migration_test (")
                .replace("CONSTRAINT ", "CONSTRAINT migration_");
        try {
            jdbc.execute(migration);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_outbox_migration_test", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS analysis_outbox_migration_test");
        }
    }

    private AnalysisOutbox pending(LocalDateTime nextAttemptAt) {
        User user = new User("발행" + UUID.randomUUID().toString().substring(0, 6), "profile/test.png");
        entityManager.persist(user);
        AnalysisJobState job = states.save(AnalysisJobState.queued(user, UUID.randomUUID().toString(), "{}"));
        String messageId = UUID.randomUUID().toString();
        return outbox.save(AnalysisOutbox.pending(job, messageId, "local.dameokja.analysis.requested.v1",
                "{\"messageId\":\"" + messageId + "\",\"payload\":{}}", nextAttemptAt));
    }

    private void cleanUp(List<AnalysisOutbox> messages) {
        for (AnalysisOutbox message : messages) {
            jdbc.update("DELETE FROM analysis_outbox WHERE analysis_outbox_id = ?", message.getId());
            jdbc.update("DELETE FROM analysis_job_states WHERE analysis_job_id = ?", message.getAnalysisJobState().getId());
            jdbc.update("DELETE FROM users WHERE user_id = ?", message.getAnalysisJobState().getUser().getId());
        }
    }
}
