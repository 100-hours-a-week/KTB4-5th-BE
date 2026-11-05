package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import com.dameokja.backend.analysis.support.AnalysisRefrigeratorFixture;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({RefrigeratorAccessService.class, AnalysisJobAcceptance.class, AnalysisJobStateAccess.class,
        UserAccessService.class, AnalysisKafkaConfig.class, AnalysisExpiredResultCleanupTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisExpiredResultCleanupTest extends MySqlJpaTest {
    private static final Instant NOW = Instant.parse("2026-10-31T03:00:00Z");
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Clock clock;
    private User requestingUser;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.withZone(BusinessTime.ZONE)).thenAnswer(invocation -> Clock.fixed(clock.instant(), BusinessTime.ZONE));
        requestingUser = userRepository.saveAndFlush(new User("정리" + UUID.randomUUID().toString().substring(0, 6), "profile"));
        AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, requestingUser.getId());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_expired_job_delete");
        String ownedJobs = "SELECT analysis_job_id FROM analysis_job_states WHERE user_id = ?";
        jdbcTemplate.update("DELETE FROM analysis_outbox WHERE analysis_job_id IN (" + ownedJobs + ")", requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE analysis_job_id IN (" + ownedJobs + ")", requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_states WHERE user_id = ?", requestingUser.getId());
        AnalysisRefrigeratorFixture.cleanUp(jdbcTemplate, requestingUser.getId());
        userRepository.deleteById(requestingUser.getId());
    }

    @Test
    void deletesAtDeadlineWithChildrenAndPreservesOtherSharedRequestAndUnexpiredJobs() {
        AnalysisJob expiredJob = accept();
        AnalysisJob futureJob = accept();
        AnalysisJob pendingJob = accept();
        setExpiry(expiredJob, BusinessTime.now(clock));
        setExpiry(futureJob, BusinessTime.now(clock).plusNanos(1000));
        assertThat(analysisJobStateAccess.deleteNextExpired()).isTrue();
        assertThat(analysisJobStateAccess.deleteNextExpired()).isFalse();
        assertCounts(expiredJob, 0);
        assertCounts(futureJob, 1);
        assertCounts(pendingJob, 1);
        assertThat(expiredJob.analysisImages().getFirst().requestId()).isEqualTo(futureJob.analysisImages().getFirst().requestId());
    }

    @Test
    void skipsLockedParentAndDeletesAnotherExpiredJob() throws Exception {
        AnalysisJob lockedJob = accept();
        AnalysisJob availableJob = accept();
        setExpiry(lockedJob, BusinessTime.now(clock).minusSeconds(2));
        setExpiry(availableJob, BusinessTime.now(clock).minusSeconds(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executorService.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT analysis_job_id FROM analysis_job_states WHERE analysis_job_id = ? FOR UPDATE", String.class, lockedJob.id());
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("lock release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(analysisJobStateAccess.deleteNextExpired()).isTrue();
                assertCounts(lockedJob, 1);
                assertCounts(availableJob, 0);
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void schedulerLimitsEachPoll() {
        AnalysisJob firstJob = accept();
        AnalysisJob secondJob = accept();
        setExpiry(firstJob, BusinessTime.now(clock).minusSeconds(1));
        setExpiry(secondJob, BusinessTime.now(clock));
        AnalysisExpiredResultScheduler scheduler = new AnalysisExpiredResultScheduler(analysisJobStateAccess, 1);
        scheduler.poll();
        assertCounts(firstJob, 0);
        assertCounts(secondJob, 1);
        scheduler.poll();
        assertCounts(secondJob, 0);
    }

    @Test
    void restoresChildrenWhenParentDeletionFails() {
        AnalysisJob expiredJob = accept();
        setExpiry(expiredJob, BusinessTime.now(clock));
        jdbcTemplate.execute("CREATE TRIGGER fail_expired_job_delete BEFORE DELETE ON analysis_job_states "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cleanup-rollback'");
        assertThatThrownBy(() -> analysisJobStateAccess.deleteNextExpired()).hasStackTraceContaining("cleanup-rollback");
        assertCounts(expiredJob, 1);
    }

    private AnalysisJob accept() {
        return analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(AnalysisImage.pending("same", "ab".repeat(32))));
    }

    private void setExpiry(AnalysisJob analysisJob, LocalDateTime expiry) {
        jdbcTemplate.update("UPDATE analysis_job_states SET expires_at = ? WHERE analysis_job_id = ?", expiry, analysisJob.id());
    }

    private void assertCounts(AnalysisJob analysisJob, int expected) {
        for (String table : List.of("analysis_job_states", "analysis_job_requests", "analysis_outbox")) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE analysis_job_id = ?", Integer.class, analysisJob.id())).isEqualTo(expected);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
    }
}
