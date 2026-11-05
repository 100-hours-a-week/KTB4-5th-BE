package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyQuotaStore;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "recipe.quota.database-start-date=2026-09-17")
class RecommendationDatabaseQuotaTest extends ServiceIntegrationTest {
    @Autowired private RecommendationDailyQuotaService quota;
    @Autowired private RecommendationDailyQuotaStore redis;
    @Autowired private RecommendationSubmissionService submission;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void serializesTwentyRequestsAndAllowsTen() throws Exception {
        Fixture participant = ownerFixture("동시회원");
        List<Callable<Object>> attempts = IntStream.range(0, 20).mapToObj(index -> (Callable<Object>) () -> {
            try {
                return reserve(participant.userId(), "request-" + index);
            } catch (CustomException exception) {
                return exception;
            }
        }).toList();
        List<Object> results = concurrently(attempts);
        assertThat(results.stream().filter(RecommendationDailyGeneration.class::isInstance)).hasSize(10);
        assertThat(results.stream().filter(CustomException.class::isInstance)).hasSize(10);
        assertThat(quota.current(participant.userId()).remaining()).isZero();
    }

    @Test
    void differentUsersCanReserveConcurrently() throws Exception {
        List<Fixture> participants = IntStream.range(0, 20).mapToObj(index -> ownerFixture("회원" + index)).toList();
        List<Callable<Object>> attempts = participants.stream().map(participant -> (Callable<Object>) () ->
                reserve(participant.userId(), "request")).toList();
        assertThat(concurrently(attempts)).allMatch(RecommendationDailyGeneration.class::isInstance);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_daily_quotas", Integer.class)).isEqualTo(20);
    }

    @Test
    void rollsBackReservationAndStateIfOutboxInsertFails() {
        Fixture participant = ownerFixture("롤백회원");
        jdbcTemplate.execute("CREATE TRIGGER reject_quota_outbox BEFORE INSERT ON recommendation_outbox FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'outbox rejected'");
        try {
            assertThatThrownBy(() -> submission.submit(participant.userId(), participant.refrigeratorId(), null)).isInstanceOf(Exception.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_quota_outbox");
        }
        assertThat(quota.current(participant.userId()).remaining()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_states", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isZero();
    }

    @Test
    void combinesLegacyIdsAndPreservesDuplicatesAndRollback() {
        Fixture participant = ownerFixture("전환회원");
        LocalDate date = LocalDate.of(2026, 9, 17);
        redis.reserve(participant.userId(), date, "legacy", 10, Duration.ofHours(12));
        jdbcTemplate.update("INSERT INTO recommendation_daily_quotas (user_id, quota_date, request_ids) VALUES (?, ?, JSON_ARRAY(?))",
                participant.userId(), date, "legacy");
        assertThat(reserve(participant.userId(), "legacy").remaining()).isEqualTo(9);
        assertThat(reserve(participant.userId(), "database").remaining()).isEqualTo(8);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            quota.reserve(participant.userId(), "rollback");
            status.setRollbackOnly();
        });
        assertThat(quota.current(participant.userId()).remaining()).isEqualTo(8);
        clock.set("2026-09-17T15:00:00Z");
        assertThat(quota.current(participant.userId()).remaining()).isEqualTo(10);
        assertThat(reserve(participant.userId(), "next-day").remaining()).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_daily_quotas", Integer.class)).isEqualTo(1);
    }

    @Test
    void rollsBackStateAndOutboxWhenQuotaIsExhausted() {
        Fixture participant = ownerFixture("접수회원");
        for (int index = 0; index < 10; index++) {
            reserve(participant.userId(), "request-" + index);
        }
        assertThatThrownBy(() -> submission.submit(participant.userId(), participant.refrigeratorId(), null)).isInstanceOf(CustomException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_states", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isZero();
    }

    private RecommendationDailyGeneration reserve(Long userId, String requestId) {
        return new TransactionTemplate(transactionManager).execute(status -> quota.reserve(userId, requestId));
    }
}
