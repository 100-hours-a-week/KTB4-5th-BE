package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.application.RecommendationDailyJobQueue.Lease;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RecommendationDailyJobQueueTest extends ServiceIntegrationTest {
    @Autowired private RecommendationDailyJobQueue queue;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void concurrentRegistrationKeepsOneJobAndItsOriginalRequestId() throws Exception {
        Fixture participant = ownerFixture("일일등록");
        assertThat(concurrently(List.of(() -> {
            register(participant);
            return true;
        }, () -> {
            register(participant);
            return true;
        }))).containsExactly(true, true);
        Lease first = lease().getFirst();
        register(participant);
        assertThat(countJobs()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT request_id FROM recommendation_daily_jobs", String.class)).isEqualTo(first.requestId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_states", Integer.class)).isZero();
    }

    @Test
    void concurrentInstancesCannotLeaseTheSameJobTwice() throws Exception {
        register(ownerFixture("일일선점"));
        List<Object> results = concurrently(List.of(() -> lease().stream().findFirst().orElse(null), () -> lease().stream().findFirst().orElse(null)));
        assertThat(results).noneMatch(Throwable.class::isInstance);
        assertThat(results.stream().filter(Lease.class::isInstance)).hasSize(1);
        assertThat(lease()).isEmpty();
    }

    @Test
    void reclaimsAtLeaseExpiryWithTheSameRequestAndRejectsTheOldOwner() {
        Fixture participant = ownerFixture("일일복구");
        register(participant);
        Lease expired = lease().getFirst();
        clock.set("2026-09-17T03:00:30Z");
        assertThat(queue.skip(expired, "OLD_OWNER")).isFalse();
        Lease recovered = lease().getFirst();
        assertThat(recovered.jobId()).isEqualTo(expired.jobId());
        assertThat(recovered.requestId()).isEqualTo(expired.requestId());
        assertThat(recovered.leaseToken()).isNotEqualTo(expired.leaseToken());
        assertThat(queue.skip(expired, "OLD_OWNER")).isFalse();
        assertThat(queue.skip(recovered, "GENERATION_IN_PROGRESS")).isTrue();
        register(participant);
        assertThat(lease()).isEmpty();
        assertThat(jdbcTemplate.queryForMap("SELECT status, reason, lease_token, lease_until FROM recommendation_daily_jobs"))
                .containsEntry("status", "SKIPPED").containsEntry("reason", "GENERATION_IN_PROGRESS")
                .containsEntry("lease_token", null).containsEntry("lease_until", null);
    }

    @Test
    void isolatesBusinessDatesAndParticipants() {
        Fixture first = ownerFixture("일일첫회원");
        Fixture second = ownerFixture("일일둘회원");
        register(first);
        register(second);
        queue.register(first.userId(), first.refrigeratorId(), BusinessTime.today(clock).plusDays(1));
        assertThat(lease()).hasSize(1);
        assertThat(lease()).hasSize(1);
        assertThat(lease()).isEmpty();
        clock.set("2026-09-17T15:00:00Z");
        assertThat(lease()).singleElement().satisfies(job -> {
            assertThat(job.userId()).isEqualTo(first.userId());
            assertThat(job.refrigeratorId()).isEqualTo(first.refrigeratorId());
            assertThat(job.businessDate()).isEqualTo(BusinessTime.today(clock));
        });
        assertThat(countJobs()).isEqualTo(3);
    }

    @Test
    void registrationAndClaimRollBackWithTheEnclosingTransaction() {
        Fixture participant = ownerFixture("일일롤백");
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            register(participant);
            assertThat(lease()).hasSize(1);
            transaction.setRollbackOnly();
        });
        assertThat(countJobs()).isZero();
    }

    @Test
    void rejectsAnInvalidBatchSizeOrLeaseDuration() {
        assertThatThrownBy(() -> queue.leaseBatch(BusinessTime.today(clock), 0, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.leaseBatch(BusinessTime.today(clock), 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private void register(Fixture participant) { queue.register(participant.userId(), participant.refrigeratorId(), BusinessTime.today(clock)); }

    private List<Lease> lease() { return queue.leaseBatch(BusinessTime.today(clock), 1, Duration.ofSeconds(30)); }

    private int countJobs() { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_daily_jobs", Integer.class); }
}
